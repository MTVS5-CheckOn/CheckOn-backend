package com.checkon.member.report.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.common.security.ParentChildAccessGuard;
import com.checkon.member.common.storage.ObjectStoragePort;
import com.checkon.member.common.storage.ObjectStorageUnavailableException;
import com.checkon.member.common.storage.StoredObjectMetadata;
import com.checkon.member.report.application.dto.ReportFileAccessResponse;
import com.checkon.member.report.domain.PublishedReport;
import com.checkon.member.report.domain.ReportFile;
import com.checkon.member.report.domain.ReportFileTokenCodec;
import com.checkon.member.report.infrastructure.persistence.MemberPublishedReportRepository;
import com.checkon.member.report.infrastructure.persistence.MemberReportFileRepository;

/**
 * PDF 열람 URL 발급. 관계를 재검증하고 파일 무결성을 확인한 뒤에만 수명이 짧은 토큰을 준다.
 *
 * <p>판정 순서 — 앞에서 걸리면 뒤는 보지 않는다:</p>
 * <ol>
 *   <li>{@link ParentChildAccessGuard} 재검증 → 실패 {@code 404}</li>
 *   <li>보고서 {@code status = 'PUBLISHED'} → 0행 {@code 404}</li>
 *   <li>발행 강사가 자녀의 활성 강사인가 → 아니면 {@code 404}</li>
 *   <li>{@code member_report_files} 행 → 0행 {@code 404} ({@code hasPdf=false} 인 경우)</li>
 *   <li>{@code head()} 의 {@code contentType}·{@code sizeBytes} 가 저장값과 같은가 →
 *       불일치 {@code 503}</li>
 *   <li>🔴 <b>checksum 재계산 후 저장값과 비교</b> → 불일치 {@code 503}, <b>발급하지 않는다</b></li>
 *   <li>토큰 발급 → {@code 201}</li>
 * </ol>
 *
 * <p>🔴 <b>6번이 fail-closed 지점이다.</b> 바이트가 바뀐 파일에 URL 을 주지 않는다.
 * 스트리밍 중간에 발견하면 이미 절반을 보낸 뒤라 못 막는다. 파일이
 * {@code max-verify-bytes} 를 넘으면 <b>검증할 수 없으므로 발급하지 않고</b> {@code 503} 이다 —
 * 검증을 건너뛰고 주는 분기를 만들지 않는다.</p>
 *
 * <p>🔴 응답에 {@code objectKey}·{@code bucket}·{@code path} 를 담는 <b>필드가 존재하지
 * 않는다</b>({@link ReportFileAccessResponse}).</p>
 */
@Service
public class ReportFileAccessService {

	private static final Logger log = LoggerFactory.getLogger(ReportFileAccessService.class);
	private static final String DOWNLOAD_PATH = "/api/v1/member/files/reports/";

	private final MemberPublishedReportRepository reports;
	private final MemberReportFileRepository files;
	private final ParentChildAccessGuard accessGuard;
	private final ObjectStoragePort storage;
	private final ReportFileTokenCodec tokenCodec;
	private final MemberReportProperties properties;
	private final Clock clock;

	public ReportFileAccessService(
		MemberPublishedReportRepository reports,
		MemberReportFileRepository files,
		ParentChildAccessGuard accessGuard,
		ObjectStoragePort storage,
		ReportFileTokenCodec tokenCodec,
		MemberReportProperties properties,
		Clock clock
	) {
		this.reports = reports;
		this.files = files;
		this.accessGuard = accessGuard;
		this.storage = storage;
		this.tokenCodec = tokenCodec;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ReportFileAccessResponse issue(
		MemberSubject subject, UUID studentId, UUID reportId
	) {
		return accessGuard.withVerifiedChild(subject, studentId, access -> {
			PublishedReport report = reports.findPublished(reportId, studentId)
				.filter(row -> access.allowsTeacher(row.teacherId()))
				.orElseThrow(ReportFileAccessService::notFound);
			ReportFile file = files.findByReport(report.id(), studentId)
				.orElseThrow(ReportFileAccessService::notFound);
			verifyIntegrity(file);
			Instant expiresAt = clock.instant().plus(properties.signedUrlTtl());
			String token = tokenCodec.issue(file.id(), access.parentId(), expiresAt);
			return new ReportFileAccessResponse(
				DOWNLOAD_PATH + token, expiresAt, file.contentType(),
				file.checksum(), file.sizeBytes(), file.pageCount());
		});
	}

	/**
	 * 🔴 저장소가 보고한 값과 DB 에 적힌 값이 하나라도 다르면 발급하지 않는다.
	 * 「그럴 수도 있으니 통과」가 없다 — 다르다는 것은 누군가 파일을 바꿨다는 뜻이다.
	 */
	private void verifyIntegrity(ReportFile file) {
		StoredObjectMetadata metadata = head(file);
		if (!file.contentType().equals(metadata.contentType())
			|| file.sizeBytes() != metadata.sizeBytes()) {
			log.error("member.report.file.metadata_mismatch fileId={} report={}",
				file.id(), file.reportId());
			throw dependencyUnavailable();
		}
		String recomputed = sha256(file);
		if (!file.checksum().equals(recomputed)) {
			log.error("member.report.file.checksum_mismatch fileId={} report={}",
				file.id(), file.reportId());
			throw dependencyUnavailable();
		}
	}

	private StoredObjectMetadata head(ReportFile file) {
		try {
			return storage.head(file.objectKey());
		}
		catch (ObjectStorageUnavailableException unavailable) {
			log.error("member.report.file.head_failed fileId={} reason={}",
				file.id(), unavailable.reasonCode());
			throw dependencyUnavailable();
		}
	}

	private String sha256(ReportFile file) {
		try {
			return storage.sha256(file.objectKey(), properties.maxVerifyBytes());
		}
		catch (ObjectStorageUnavailableException unavailable) {
			// 상한 초과도 여기로 온다 — 검증할 수 없으면 발급하지 않는다.
			log.error("member.report.file.verify_failed fileId={} reason={}",
				file.id(), unavailable.reasonCode());
			throw dependencyUnavailable();
		}
	}

	private static MemberException dependencyUnavailable() {
		return new MemberException(MemberErrorCode.DEPENDENCY_UNAVAILABLE,
			"report file is not available");
	}

	private static MemberException notFound() {
		return new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND, "report file not found");
	}
}
