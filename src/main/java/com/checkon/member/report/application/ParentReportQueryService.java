package com.checkon.member.report.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.common.security.ParentChildAccessGuard;
import com.checkon.member.common.security.ParentChildAccessGuard.ChildAccess;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;
import com.checkon.member.report.application.dto.ReportDetailResponse;
import com.checkon.member.report.application.dto.ReportListPage;
import com.checkon.member.report.application.dto.ReportSectionResponse;
import com.checkon.member.report.application.dto.ReportSummaryResponse;
import com.checkon.member.report.application.dto.ReportSummaryResponse.TeacherRef;
import com.checkon.member.report.domain.PublishedReport;
import com.checkon.member.report.domain.PublishedReportSection;
import com.checkon.member.report.infrastructure.persistence.MemberPublishedReportRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 학부모의 발행 보고서 목록·상세.
 *
 * <p>🔴 <b>부재·권한 없음·미발행을 전부 {@code 404 RESOURCE_NOT_FOUND} 로 낸다</b>
 * (설계 §6-4 불변식 3). 미발행을 {@code 403} 으로 내면 「그 달 보고서가 존재는 한다」를
 * 알려주는 것이다.</p>
 *
 * <p>🔴 관계 재검증은 {@link ParentChildAccessGuard} 한 곳이 한다 — 매 요청, 트랜잭션 안에서.
 * 목록과 상세가 같은 함수를 쓴다.</p>
 *
 * <p>🔴 목록 진입에서 자기 자녀의 발행 알림 outbox 를 인라인 drain 한다(설계 §11-1).
 * <b>{@code @Transactional} 로 read 트랜잭션을 열지 않는다</b> — 그러면 drain 의 write 가
 * read-only 커넥션에 삼켜지고, 무엇보다 자기 호출은 프록시를 통과하지 않아 어노테이션이
 * 통째로 무시된다(PR7 실측). {@link TransactionTemplate} 로 drain 이후에 별도 트랜잭션을 연다.</p>
 *
 * <p>🔴 {@code sections[].data} 는 저장된 JSONB 를 <b>그대로</b> 반환한다. 조회 시점에
 * 재계산하거나 PR7 집계와 대조해 보정하지 않는다 — 발행 스냅샷과 현재 집계가 다른 것은
 * 버그가 아니라 설계다.</p>
 */
@Service
public class ParentReportQueryService {

	private final MemberPublishedReportRepository reports;
	private final ParentChildAccessGuard accessGuard;
	private final RosterRelationshipPort rosterRelationships;
	private final ReportPublicationNotifier notifier;
	private final MemberReportProperties properties;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate readOnlyTransactionTemplate;

	public ParentReportQueryService(
		MemberPublishedReportRepository reports,
		ParentChildAccessGuard accessGuard,
		RosterRelationshipPort rosterRelationships,
		ReportPublicationNotifier notifier,
		MemberReportProperties properties,
		ObjectMapper objectMapper,
		PlatformTransactionManager transactionManager
	) {
		this.reports = reports;
		this.accessGuard = accessGuard;
		this.rosterRelationships = rosterRelationships;
		this.notifier = notifier;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
		// 🔴 drain 이후의 read 트랜잭션이라 REQUIRES_NEW. read-only 힌트를 커넥션에 반영한다.
		this.readOnlyTransactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readOnlyTransactionTemplate.setReadOnly(true);
	}

	public ReportListPage list(
		MemberSubject subject, UUID studentId, UUID teacherFilter, String cursor, Integer limit
	) {
		// 🔴 검증을 먼저 한다. limit 이 틀렸는데 알림부터 발행하면 400 응답과 부수효과가 갈린다.
		int safeLimit = validatedLimit(limit);
		Cursor decoded = decodeCursor(cursor);
		notifier.drainForChild(subject, studentId);
		return readOnlyTransactionTemplate.execute(status ->
			accessGuard.withVerifiedChild(subject, studentId,
				access -> page(access, teacherFilter, decoded, safeLimit)));
	}

	public ReportDetailResponse detail(MemberSubject subject, UUID studentId, UUID reportId) {
		return readOnlyTransactionTemplate.execute(status ->
			accessGuard.withVerifiedChild(subject, studentId, access -> {
				PublishedReport report = reports.findPublished(reportId, studentId)
					.filter(row -> access.allowsTeacher(row.teacherId()))
					.orElseThrow(ParentReportQueryService::notFound);
				ReportSummaryResponse summary = toSummary(report);
				List<ReportSectionResponse> sections = reports.findSections(report.id()).stream()
					.map(this::toSection).toList();
				return ReportDetailResponse.of(summary, report.snapshotVersion(), sections);
			}));
	}

	private ReportListPage page(
		ChildAccess access, UUID teacherFilter, Cursor cursor, int limit
	) {
		// 🔴 teacherId 는 권한이 아니라 필터다. 허용 집합 밖을 가리키면 빈 목록이다 —
		//    「그 강사의 보고서가 있는지」 자체를 알려주지 않으므로 404 와 정보량이 같다.
		if (teacherFilter != null && !access.allowsTeacher(teacherFilter)) {
			return new ReportListPage(List.of(), null, false);
		}
		List<UUID> allowed = List.copyOf(access.activeTeacherIds());
		List<PublishedReport> rows = reports.findPage(access.studentId(), allowed, teacherFilter,
			cursor == null ? null : cursor.publishedAt(),
			cursor == null ? null : cursor.id(),
			limit);
		List<ReportSummaryResponse> items = new ArrayList<>();
		for (PublishedReport row : rows) {
			items.add(toSummary(row));
		}
		String next = rows.size() < limit ? null : encodeCursor(rows.get(rows.size() - 1));
		return new ReportListPage(List.copyOf(items), next, next != null);
	}

	private ReportSummaryResponse toSummary(PublishedReport report) {
		return new ReportSummaryResponse(
			report.id(), report.reportMonth(), report.revision(),
			ReportSummaryResponse.PUBLISHED, report.publishedAt(),
			teacherRef(report.teacherId()), reports.hasFile(report.id()));
	}

	/**
	 * 🔴 강사 표시 이름을 못 읽으면 지어내지 않는다. 계약이 {@code displayName} 을
	 * {@code required} 로 두므로 그때는 보고서 자체를 내보내지 않고 {@code 404} 다 —
	 * 이름 자리에 「알 수 없음」을 넣는 것이 위조다.
	 */
	private TeacherRef teacherRef(UUID teacherId) {
		Optional<TeacherSummaryView> teacher = rosterRelationships.findTeacherSummary(teacherId);
		return teacher.map(view -> new TeacherRef(view.teacherId(), view.displayName(), null))
			.orElseThrow(ParentReportQueryService::notFound);
	}

	private ReportSectionResponse toSection(PublishedReportSection section) {
		return new ReportSectionResponse(
			section.kind(), section.title(), section.status(), section.body(),
			readJson(section.content()), section.evidenceRefs(), section.unproducedReason());
	}

	/** 저장된 JSONB 를 그대로 되돌린다. 못 읽으면 지어내지 않고 {@code null} 이다. */
	private JsonNode readJson(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(raw);
		}
		catch (JacksonException malformed) {
			return null;
		}
	}

	/** 🔴 상한 초과는 조용히 깎지 않고 {@code 400} 이다(분기표 §0-5). */
	private int validatedLimit(Integer limit) {
		int value = limit == null ? properties.listDefaultLimit() : limit;
		if (value < 1 || value > properties.listMaxLimit()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"limit must be between 1 and " + properties.listMaxLimit());
		}
		return value;
	}

	static String encodeCursor(PublishedReport report) {
		String raw = report.publishedAt().toEpochMilli() + ":" + report.id();
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/** 🔴 형식 오류·위조는 {@code 400} 이다. 조용히 첫 페이지로 되돌리지 않는다. */
	static Cursor decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor),
				StandardCharsets.UTF_8);
			int mark = raw.indexOf(':');
			if (mark <= 0) {
				throw new IllegalArgumentException("cursor");
			}
			return new Cursor(
				Instant.ofEpochMilli(Long.parseLong(raw.substring(0, mark))),
				UUID.fromString(raw.substring(mark + 1)));
		}
		catch (IllegalArgumentException malformed) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST, "cursor is malformed");
		}
	}

	private static MemberException notFound() {
		return new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND, "report not found");
	}

	record Cursor(Instant publishedAt, UUID id) {
	}
}
