package com.checkon.publication.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.publication.domain.PublishableSection;

/**
 * V45 학부모 원장에 발행 스냅샷을 쓴다. 🔴 <b>강사 컨텍스트가 열린 트랜잭션 안에서만</b> 돈다.
 *
 * <p>🔴 <b>쓰는 순서가 정해져 있다.</b> V45 정책과 복합 FK 가 그렇게 강제한다:</p>
 * <ol>
 *   <li>보고서 INSERT — {@code status='DRAFT'}, {@code published_at=NULL}.
 *       강사 INSERT 정책은 {@code teacher_id = current_checkon_teacher_id()} 다</li>
 *   <li>섹션 INSERT — {@code published_at=NULL}.
 *       🔴 섹션 INSERT 정책이 {@code published_at IS NULL} 을 요구한다</li>
 *   <li>보고서 UPDATE — {@code PUBLISHED} + {@code published_at=T}.
 *       🔴 UPDATE 정책 USING 이 {@code status <> 'PUBLISHED'} 라 여기까지만 가능하고,
 *       이 뒤로는 이 행을 <b>영영 못 고친다</b></li>
 *   <li>섹션 UPDATE — {@code published_at=T}. 🔴 부모와 <b>같은 T</b> 여야 한다.
 *       복합 FK {@code (report_id, published_at)} 가 갈림을 INSERT 실패로 만든다</li>
 *   <li>outbox INSERT — {@code published_at=T}, {@code PENDING}.
 *       PR9 의 {@code ReportNotificationRunner} 가 학부모 컨텍스트에서 드레인한다</li>
 * </ol>
 *
 * <p>🔴 <b>{@code T} 를 한 번만 뜬다.</b> {@code Instant.now()} 를 두 번 부르면 복합 FK 위반이다.</p>
 *
 * <p>🔴 <b>{@code member_report_files} 에 쓰지 않는다.</b> 승우님
 * {@code monthly_report_artifacts.storage_key} 는 그쪽 저장소를 가리키고 우리
 * {@code object_key} 는 우리 {@code storageRoot} 기준이다 — <b>서로 다른 저장소다.</b>
 * 행만 만들면 학부모가 다운로드에서 503/404 를 받는다. 계약이 「PDF 없음 → 200 +
 * {@code hasPdf:false}」를 이미 허용한다(분기표 §4) → MB-62.</p>
 *
 * <p>🔴 <b>UPDATE 의 반환 행 수를 확인한다.</b> RLS 는 예외가 아니라 0행으로 거절한다 —
 * 안 보면 「고쳤다고 생각했는데 안 고쳐진」 상태로 조용히 지나간다.</p>
 */
@Repository
public class PublishedReportWriter {

	private static final String INSERT_REPORT = """
		INSERT INTO member_published_reports
		    (id, student_id, teacher_id, report_month, month_zone, revision, status,
		     snapshot_version, created_at, updated_at, published_at)
		VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, NULL)
		""";

	private static final String INSERT_SECTION = """
		INSERT INTO member_published_report_sections
		    (id, report_id, student_id, teacher_id, published_at, kind, title, ordinal,
		     status, body, content, evidence_refs, unproduced_reason, created_at)
		VALUES (?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
		""";

	private static final String PUBLISH_REPORT = """
		UPDATE member_published_reports
		SET status = 'PUBLISHED', published_at = ?, updated_at = ?
		WHERE id = ? AND teacher_id = ?
		""";

	private static final String PUBLISH_SECTIONS = """
		UPDATE member_published_report_sections
		SET published_at = ?
		WHERE report_id = ? AND teacher_id = ?
		""";

	private static final String INSERT_OUTBOX = """
		INSERT INTO member_report_publication_outbox
		    (id, report_id, student_id, teacher_id, published_at, status, created_at)
		VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
		""";

	/**
	 * 🔴 <b>멱등 판정.</b> 같은 (학생, 강사, 달) 로 이미 발행한 것이 있으면 다시 만들지 않는다.
	 * 상태를 가리지 않는 이유는 초안·실패본이 남아 있어도 같은 키의 두 번째 행을 만들면
	 * {@code uq_member_published_reports_revision} 위반이기 때문이다.
	 */
	private static final String EXISTS_FOR_MONTH = """
		SELECT 1 FROM member_published_reports
		WHERE student_id = ? AND teacher_id = ? AND report_month = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public PublishedReportWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean alreadyPublished(UUID studentId, UUID teacherId, String reportMonth) {
		return !jdbcTemplate.queryForList(
			EXISTS_FOR_MONTH, Integer.class, studentId, teacherId, reportMonth).isEmpty();
	}

	/**
	 * 위 다섯 단계를 한 트랜잭션에서 돈다.
	 *
	 * @param publishedAt 🔴 호출자가 <b>한 번 떠서</b> 넘긴 시각. 여기서 다시 뜨지 않는다
	 * @return 만든 보고서 id
	 */
	public UUID publish(
		UUID studentId, UUID teacherId, String reportMonth, String monthZone,
		int revision, String snapshotVersion, List<PublishableSection> sections,
		Instant publishedAt
	) {
		OffsetDateTime at = OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC);
		UUID reportId = UUID.randomUUID();
		jdbcTemplate.update(INSERT_REPORT, reportId, studentId, teacherId, reportMonth,
			monthZone, revision, snapshotVersion, at, at);
		for (PublishableSection section : sections) {
			jdbcTemplate.update(INSERT_SECTION, UUID.randomUUID(), reportId, studentId,
				teacherId, section.kind(), section.ordinal(), section.status(), section.body(),
				section.content(), toJsonArray(section.evidenceRefs()),
				section.unproducedReason(), at);
		}
		requireOneRow(jdbcTemplate.update(PUBLISH_REPORT, at, at, reportId, teacherId),
			"report could not be published (RLS or state)", reportId);
		int published = jdbcTemplate.update(PUBLISH_SECTIONS, at, reportId, teacherId);
		if (published != sections.size()) {
			throw new IllegalStateException(
				"published section count " + published + " != " + sections.size()
					+ " for report " + reportId);
		}
		jdbcTemplate.update(INSERT_OUTBOX, UUID.randomUUID(), reportId, studentId, teacherId,
			at, at);
		return reportId;
	}

	private static void requireOneRow(int updated, String message, UUID reportId) {
		if (updated != 1) {
			throw new IllegalStateException(message + ": " + reportId);
		}
	}

	/**
	 * 🔴 문자열 배열을 JSON 으로 직렬화한다. Jackson 을 여기 끌어들이지 않는 이유는 값이
	 * 「따옴표·역슬래시만 escape 하면 되는 평범한 문자열」이기 때문이다 —
	 * 형태는 V45 의 {@code jsonb_typeof(evidence_refs) = 'array'} CHECK 가 고정한다.
	 */
	private static String toJsonArray(List<String> values) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				json.append(',');
			}
			json.append('"')
				.append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
				.append('"');
		}
		return json.append(']').toString();
	}
}
