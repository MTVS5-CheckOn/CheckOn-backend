package com.checkon.member.report.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.checkon.member.report.domain.ReportFile;

/**
 * 발행 PDF 메타데이터 조회. 🔴 조회는 RLS 컨텍스트가 열린 트랜잭션 안에서만 돈다.
 *
 * <p>🔴 두 조회 모두 {@code published_at IS NOT NULL} 을 건다. V45 의 부모 SELECT 정책이
 * 이미 같은 것을 강제하지만, 이 값이 부모의 {@code published_at} 과 복합 FK 로 묶여 있어
 * 「행이 있는데 부모는 미발행」이라는 상태가 존재할 수 없다는 사실을 SQL 에도 남긴다.</p>
 */
@Repository
public class MemberReportFileRepository {

	private static final String COLUMNS = """
		id, report_id, object_key, checksum, content_type, size_bytes, page_count
		""";

	private static final String FIND_BY_REPORT = """
		SELECT %s FROM member_report_files
		WHERE report_id = ? AND student_id = ? AND published_at IS NOT NULL
		""".formatted(COLUMNS);

	/**
	 * 다운로드 경로 전용. 🔴 여기서 {@code student_id} 를 인자로 받지 않는 이유 — 다운로드는
	 * 파일 id 만 아는 상태에서 시작하고, 그 파일이 누구 것인지를 <b>RLS 가</b> 판정한다.
	 * 범위가 열려 있지 않으면 0행이다.
	 */
	private static final String FIND_BY_ID = """
		SELECT %s FROM member_report_files
		WHERE id = ? AND published_at IS NOT NULL
		""".formatted(COLUMNS);

	private final JdbcTemplate jdbcTemplate;

	public MemberReportFileRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<ReportFile> findByReport(UUID reportId, UUID studentId) {
		return first(jdbcTemplate.query(FIND_BY_REPORT, mapper(), reportId, studentId));
	}

	public Optional<ReportFile> findById(UUID fileId) {
		return first(jdbcTemplate.query(FIND_BY_ID, mapper(), fileId));
	}

	private static Optional<ReportFile> first(List<ReportFile> rows) {
		return rows.stream().findFirst();
	}

	private static RowMapper<ReportFile> mapper() {
		return (rs, rowNum) -> new ReportFile(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getString(3),
			rs.getString(4),
			rs.getString(5),
			rs.getLong(6),
			// 🔴 SQL NULL 을 0 으로 읽지 않는다. getInt 는 NULL 을 0 으로 준다.
			rs.getObject(7, Integer.class));
	}
}
