package com.checkon.member.report.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.report.domain.ReportPublicationOutboxRow;

/**
 * 발행 알림 대기열. 🔴 학부모 컨텍스트에서 자기 자녀 행만 보이고 고칠 수 있다(V45 §5-4).
 *
 * <p>🔴 {@code report_month} 는 조인이 아니라 <b>대기열 행 자체</b>에서 오지 않는다 —
 * 알림 문구에 달을 넣어야 해서 보고서에서 읽는다. 두 테이블 모두 같은 학부모 범위 정책 아래
 * 있으므로 같은 트랜잭션에서 함께 보인다.</p>
 */
@Repository
public class MemberReportPublicationOutboxRepository {

	private static final String FIND_PENDING = """
		SELECT outbox.id, outbox.report_id, outbox.student_id, outbox.teacher_id,
		       report.report_month, outbox.attempt_count
		FROM member_report_publication_outbox outbox
		JOIN member_published_reports report ON report.id = outbox.report_id
		WHERE outbox.student_id = ? AND outbox.status = 'PENDING'
		ORDER BY outbox.created_at, outbox.id
		LIMIT ?
		""";

	private static final String MARK_DONE = """
		UPDATE member_report_publication_outbox
		SET status = 'DONE', processed_at = ?
		WHERE id = ? AND status = 'PENDING'
		""";

	/**
	 * 🔴 재시도 상한을 넘으면 {@code FAILED} 로 고정한다. 무한 재시도 금지.
	 * 상한 판정을 SQL 안에서 하는 이유는 애플리케이션이 읽은 {@code attempt_count} 와
	 * 실제 값이 갈릴 수 있어서다(동시 drain).
	 */
	private static final String MARK_FAILED = """
		UPDATE member_report_publication_outbox
		SET attempt_count = attempt_count + 1,
		    last_error_code = ?,
		    status = CASE WHEN attempt_count + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
		    processed_at = CASE WHEN attempt_count + 1 >= ? THEN ? ELSE processed_at END
		WHERE id = ? AND status = 'PENDING'
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberReportPublicationOutboxRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<ReportPublicationOutboxRow> findPending(UUID studentId, int limit) {
		return jdbcTemplate.query(FIND_PENDING, (rs, rowNum) -> new ReportPublicationOutboxRow(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getObject(4, UUID.class),
			rs.getString(5),
			rs.getInt(6)), studentId, limit);
	}

	public void markDone(UUID outboxId, Instant now) {
		jdbcTemplate.update(MARK_DONE, at(now), outboxId);
	}

	public void markFailed(UUID outboxId, String errorCode, int retryLimit, Instant now) {
		jdbcTemplate.update(MARK_FAILED, errorCode, retryLimit, retryLimit, at(now), outboxId);
	}

	private static OffsetDateTime at(Instant now) {
		return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
	}
}
