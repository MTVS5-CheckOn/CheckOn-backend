package com.checkon.member.report.application;

import java.io.InputStream;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.storage.ObjectStoragePort;
import com.checkon.member.common.storage.ObjectStorageUnavailableException;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.ChildLinkView;
import com.checkon.member.report.domain.PublishedReport;
import com.checkon.member.report.domain.ReportFile;
import com.checkon.member.report.domain.ReportFileToken;
import com.checkon.member.report.domain.ReportFileTokenCodec;
import com.checkon.member.report.infrastructure.persistence.MemberPublishedReportRepository;
import com.checkon.member.report.infrastructure.persistence.MemberReportFileRepository;

/**
 * 서명 URL 의 착지점. 🔴 <b>세션이 없는 공개 경로</b>다 — PDF 뷰어는
 * {@code Authorization} 헤더를 못 붙이므로 {@code MemberSecurityConfiguration} 에서
 * {@code permitAll} 이고, 보안은 <b>HMAC + 짧은 TTL + 다운로드 시점 관계 재검증</b>에 있다.
 *
 * <p>검증 순서 — 어느 단계 실패든 <b>{@code 404}</b> 다. 만료·위조·관계종료·부재를 구분하면
 * 토큰 유효성을 탐색당한다(분기표 §4).</p>
 * <ol>
 *   <li>서명 ({@code MessageDigest.isEqual} — 조기 반환 비교 금지)</li>
 *   <li>만료 (주입된 {@code Clock})</li>
 *   <li>🔴 <b>관계 재검증</b> — 토큰의 {@code parentProfileId} 로 학부모 컨텍스트를 열고
 *       <b>지금 활성인</b> 자녀 목록을 다시 읽는다. 발급 후 관계가 끊기면 그 자녀가 목록에서
 *       빠지고 같은 URL 이 그 순간부터 404 다</li>
 *   <li>파일 로드 — 자녀 범위 안에서만 보인다. {@code published_at IS NOT NULL} 과 학부모
 *       SELECT 정책이 함께 판정한다</li>
 *   <li>스트리밍</li>
 * </ol>
 *
 * <p>🔴 <b>DB 컨텍스트는 토큰의 {@code parentProfileId} 로만 세팅한다.</b> 쿼리 파라미터·헤더·
 * 경로의 어떤 값도 컨텍스트가 되지 않는다. 그 값은 서명으로 묶여 있어 위조가 불가능하다.</p>
 *
 * <p>🔴 <b>토큰에 {@code studentId} 를 넣지 않는다.</b> 넣으면 범위를 한 번에 열 수 있어
 * 편하지만, 토큰이 들고 다니는 정보가 늘어난다. 대신 학부모의 <b>현재</b> 활성 자녀를 훑어
 * 범위를 연다 — 관계 재검증이 조회의 부산물이 아니라 <b>경로 그 자체</b>가 된다.
 * 자녀 수는 한 자릿수라 왕복 비용도 그 범위다(MB-39 와 같은 판단).</p>
 */
@Service
public class ReportFileDownloadService {

	private static final Logger log = LoggerFactory.getLogger(ReportFileDownloadService.class);

	private final MemberReportFileRepository files;
	private final MemberPublishedReportRepository reports;
	private final RosterRelationshipPort rosterRelationships;
	private final MemberDatabaseContext databaseContext;
	private final ReportFileTokenCodec tokenCodec;
	private final ObjectStoragePort storage;
	private final Clock clock;
	private final TransactionTemplate readOnlyTransactionTemplate;

	public ReportFileDownloadService(
		MemberReportFileRepository files,
		MemberPublishedReportRepository reports,
		RosterRelationshipPort rosterRelationships,
		MemberDatabaseContext databaseContext,
		ReportFileTokenCodec tokenCodec,
		ObjectStoragePort storage,
		Clock clock,
		PlatformTransactionManager transactionManager
	) {
		this.files = files;
		this.reports = reports;
		this.rosterRelationships = rosterRelationships;
		this.databaseContext = databaseContext;
		this.tokenCodec = tokenCodec;
		this.storage = storage;
		this.clock = clock;
		this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
		this.readOnlyTransactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readOnlyTransactionTemplate.setReadOnly(true);
	}

	public Download open(String token) {
		ReportFileToken verified = tokenCodec.verify(token, clock.instant())
			.orElseThrow(ReportFileDownloadService::notFound);
		ResolvedFile resolved = readOnlyTransactionTemplate.execute(status -> resolve(verified));
		if (resolved == null) {
			throw notFound();
		}
		return stream(resolved);
	}

	private ResolvedFile resolve(ReportFileToken token) {
		databaseContext.setCurrentParent(token.parentProfileId());
		// 🔴 「지금」 활성인 자녀만 나온다. 관계가 끝났으면 여기서 이미 빠져 있다.
		for (ChildLinkView child : rosterRelationships.findActiveChildren(
			token.parentProfileId())) {
			Optional<ResolvedFile> found = tryChild(token, child.studentProfileId());
			if (found.isPresent()) {
				return found.get();
			}
		}
		throw notFound();
	}

	private Optional<ResolvedFile> tryChild(ReportFileToken token, UUID studentId) {
		return databaseContext.withVerifiedChildScope(token.parentProfileId(), studentId, () -> {
			Optional<ReportFile> file = files.findById(token.reportFileId());
			if (file.isEmpty()) {
				return Optional.empty();
			}
			// 발행본이 아니면 이름을 만들 근거가 없다 — 그리고 파일도 보이면 안 된다.
			return reports.findPublished(file.get().reportId(), studentId)
				.map(report -> new ResolvedFile(file.get(), downloadName(report)));
		});
	}

	/**
	 * {@code report-2026-08-r1.pdf}. 🔴 실명·별칭·공개 학생 ID 를 넣지 않는다 — 파일명은
	 * 다운로드 폴더에 남고 공유될 때 그대로 따라간다.
	 */
	private static String downloadName(PublishedReport report) {
		return "report-" + report.reportMonth() + "-r" + report.revision() + ".pdf";
	}

	private Download stream(ResolvedFile resolved) {
		try {
			InputStream body = storage.open(resolved.file().objectKey());
			return new Download(body, resolved.file().contentType(),
				resolved.file().sizeBytes(), resolved.downloadName());
		}
		catch (ObjectStorageUnavailableException unavailable) {
			log.error("member.report.download.failed fileId={} reason={}",
				resolved.file().id(), unavailable.reasonCode());
			throw new MemberException(MemberErrorCode.DEPENDENCY_UNAVAILABLE,
				"report file is not available");
		}
	}

	private static MemberException notFound() {
		return new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND, "report file not found");
	}

	private record ResolvedFile(ReportFile file, String downloadName) {
	}

	/** 스트리밍 응답 한 벌. 본문은 컨트롤러가 닫는다. */
	public record Download(
		InputStream body, String contentType, long sizeBytes, String downloadName
	) {
	}
}
