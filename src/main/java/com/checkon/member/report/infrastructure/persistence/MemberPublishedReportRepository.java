package com.checkon.member.report.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.checkon.member.report.domain.PublishedReport;
import com.checkon.member.report.domain.PublishedReportSection;

/**
 * 발행 보고서·섹션 조회. 🔴 조회는 반드시 RLS 컨텍스트가 열린 트랜잭션 안에서 돈다 —
 * 밖에서 부르면 예외가 아니라 <b>조용히 0행</b>이다(설계 §6-4-3).
 *
 * <p>🔴 WHERE 절의 {@code status = 'PUBLISHED'} 는 <b>중복 방어</b>다. 실제 은닉은 V45 의
 * {@code member_published_reports_member_parent_scope_select} 술어가 한다 — 이 줄을 지워도
 * 학부모 컨텍스트에서는 여전히 0건이다(MB-57). 그래도 남기는 이유는 강사 컨텍스트나 관리자
 * 커넥션에서 이 리포지토리를 재사용할 때 조용히 초안이 섞이는 것을 막기 위해서다.</p>
 */
@Repository
public class MemberPublishedReportRepository {

	private static final String COLUMNS = """
		id, student_id, teacher_id, report_month, month_zone,
		revision, snapshot_version, published_at
		""";

	/**
	 * 🔴 cursor 는 {@code (published_at, id)} 튜플 비교다. 정렬이
	 * {@code published_at DESC, id DESC} 라 「그 값보다 작은 것」을 다음 페이지로 잡는다.
	 * {@code teacherIds} 는 권한이 아니라 필터지만, 빈 배열이면 결과도 0건이다 —
	 * 「필터가 비었으니 전체」로 되돌리지 않는다.
	 */
	private static final String FIND_PAGE = """
		SELECT %s FROM member_published_reports
		WHERE student_id = ?
		  AND status = 'PUBLISHED'
		  AND teacher_id = ANY (string_to_array(?, ',')::uuid[])
		  AND (?::uuid IS NULL OR teacher_id = ?::uuid)
		  AND (?::timestamptz IS NULL
		       OR (published_at, id) < (?::timestamptz, ?::uuid))
		ORDER BY published_at DESC, id DESC
		LIMIT ?
		""".formatted(COLUMNS);

	private static final String FIND_ONE = """
		SELECT %s FROM member_published_reports
		WHERE id = ? AND student_id = ? AND status = 'PUBLISHED'
		""".formatted(COLUMNS);

	private static final String FIND_SECTIONS = """
		SELECT kind, title, ordinal, status, body, content::text,
		       evidence_refs::text, unproduced_reason
		FROM member_published_report_sections
		WHERE report_id = ? AND published_at IS NOT NULL
		ORDER BY ordinal, kind
		""";

	private static final String HAS_FILE = """
		SELECT 1 FROM member_report_files
		WHERE report_id = ? AND published_at IS NOT NULL
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberPublishedReportRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<PublishedReport> findPage(
		UUID studentId,
		List<UUID> allowedTeacherIds,
		UUID teacherFilter,
		Instant cursorPublishedAt,
		UUID cursorId,
		int limit
	) {
		if (allowedTeacherIds.isEmpty()) {
			return List.of();
		}
		// 🔴 목록을 배열 파라미터로 넘기지 않는다. 쉼표로 이어 한 개로 보내고 PostgreSQL 이
		//    배열로 되돌린다 — 드라이버의 배열 타입 매핑에 기대지 않는다(PR1 verifier 선례).
		String teacherIds = allowedTeacherIds.stream().map(UUID::toString)
			.collect(java.util.stream.Collectors.joining(","));
		String filter = teacherFilter == null ? null : teacherFilter.toString();
		String cursorAt = cursorPublishedAt == null
			? null : cursorPublishedAt.atOffset(java.time.ZoneOffset.UTC).toString();
		String cursorRef = cursorId == null ? null : cursorId.toString();
		return jdbcTemplate.query(FIND_PAGE, mapper(),
			studentId, teacherIds,
			filter, filter,
			cursorAt, cursorAt, cursorRef,
			limit);
	}

	public Optional<PublishedReport> findPublished(UUID reportId, UUID studentId) {
		List<PublishedReport> rows = jdbcTemplate.query(FIND_ONE, mapper(), reportId, studentId);
		return rows.stream().findFirst();
	}

	public List<PublishedReportSection> findSections(UUID reportId) {
		return jdbcTemplate.query(FIND_SECTIONS, (rs, rowNum) -> new PublishedReportSection(
			rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
			rs.getString(5), rs.getString(6),
			EvidenceRefs.parse(rs.getString(7)), rs.getString(8)), reportId);
	}

	/** {@code hasPdf} — 행 존재 여부다. 저장소 가용성과 무관하다(계약 §4). */
	public boolean hasFile(UUID reportId) {
		return !jdbcTemplate.queryForList(HAS_FILE, Integer.class, reportId).isEmpty();
	}

	private static RowMapper<PublishedReport> mapper() {
		return (ResultSet rs, int rowNum) -> new PublishedReport(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getString(4),
			rs.getString(5),
			rs.getInt(6),
			rs.getString(7),
			instant(rs, 8));
	}

	private static Instant instant(ResultSet rs, int index) throws SQLException {
		OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	/**
	 * {@code evidence_refs} 는 JSONB 배열이다. 🔴 Jackson 을 여기 끌어들이지 않는다 —
	 * 값은 문자열 배열이고 형태는 V45 의 {@code jsonb_typeof = 'array'} CHECK 가 고정한다.
	 */
	static final class EvidenceRefs {

		private EvidenceRefs() {
		}

		static List<String> parse(String json) {
			if (json == null || json.isBlank()) {
				return List.of();
			}
			List<String> values = new ArrayList<>();
			java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
			while (matcher.find()) {
				values.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
			}
			return List.copyOf(values);
		}
	}
}
