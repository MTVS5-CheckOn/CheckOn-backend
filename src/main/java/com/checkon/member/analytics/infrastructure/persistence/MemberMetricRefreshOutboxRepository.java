package com.checkon.member.analytics.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.analytics.domain.MetricRefreshOutboxRow;

/**
 * {@code member_metric_refresh_outbox} 접근. 학생 self 컨텍스트에서 쓰기/읽기 모두 가능하다.
 *
 * <p>🔴 부분 unique {@code uq_member_metric_refresh_outbox_pending} 이 같은 달 PENDING 은 한 행으로
 * 강제한다. 학생이 같은 달을 여러 번 제출해도 outbox 는 조용히 합쳐진다 — 배치가 그 달을 한 번만
 * 재계산한다.</p>
 *
 * <p>🔴 INSERT 는 <b>{@code ON CONFLICT DO NOTHING}</b>. 이미 PENDING 이 있으면 새 행을 넣지 않는다.
 * 예외로 취급하지 않는다 — "합쳐졌다"는 정상 상태.</p>
 */
@Repository
public class MemberMetricRefreshOutboxRepository {

	private static final String COLUMNS =
		"id, teacher_id, student_id, month, month_zone, reason, source_ref, status,"
			+ " attempt_count, last_error_code, created_at, processed_at";

	private static final String INSERT_PENDING = """
		INSERT INTO member_metric_refresh_outbox
			(teacher_id, student_id, month, month_zone, reason, source_ref, status,
			 attempt_count, created_at)
		VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
		ON CONFLICT (teacher_id, student_id, month) WHERE status = 'PENDING' DO NOTHING
		""";

	private static final String FIND_PENDING_FOR_STUDENT = """
		SELECT %s FROM member_metric_refresh_outbox
		WHERE student_id = ? AND status = 'PENDING'
		ORDER BY created_at ASC, id ASC
		LIMIT ?
		""".formatted(COLUMNS);

	private static final String MARK_DONE = """
		UPDATE member_metric_refresh_outbox
		SET status = 'DONE',
		    attempt_count = attempt_count + 1,
		    last_error_code = NULL,
		    processed_at = ?
		WHERE id = ?
		""";

	private static final String MARK_FAILED_INCREMENT = """
		UPDATE member_metric_refresh_outbox
		SET status = CASE WHEN attempt_count + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
		    attempt_count = attempt_count + 1,
		    last_error_code = ?,
		    processed_at = CASE WHEN attempt_count + 1 >= ? THEN ? ELSE processed_at END
		WHERE id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberMetricRefreshOutboxRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insertPending(
		UUID teacherId, UUID studentId, String month, String monthZone,
		String reason, String sourceRef, java.time.Instant createdAt
	) {
		jdbcTemplate.update(INSERT_PENDING,
			teacherId, studentId, month, monthZone, reason, sourceRef,
			OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
	}

	public List<MetricRefreshOutboxRow> findPending(UUID studentId, int limit) {
		return jdbcTemplate.query(FIND_PENDING_FOR_STUDENT,
			(rs, rowNum) -> map(rs), studentId, limit);
	}

	public void markDone(UUID id, java.time.Instant processedAt) {
		jdbcTemplate.update(MARK_DONE,
			OffsetDateTime.ofInstant(processedAt, ZoneOffset.UTC), id);
	}

	/** 실패 시 attemptCount 를 증가시키고, retryLimit 이상이면 FAILED 로 고정한다. */
	public void markFailed(
		UUID id, String errorCode, int retryLimit, java.time.Instant processedAt
	) {
		jdbcTemplate.update(MARK_FAILED_INCREMENT,
			retryLimit, errorCode, retryLimit,
			OffsetDateTime.ofInstant(processedAt, ZoneOffset.UTC), id);
	}

	private static MetricRefreshOutboxRow map(java.sql.ResultSet rs)
		throws java.sql.SQLException {
		OffsetDateTime processed = rs.getObject("processed_at", OffsetDateTime.class);
		return new MetricRefreshOutboxRow(
			rs.getObject("id", UUID.class),
			rs.getObject("teacher_id", UUID.class),
			rs.getObject("student_id", UUID.class),
			rs.getString("month"),
			rs.getString("month_zone"),
			rs.getString("reason"),
			rs.getString("source_ref"),
			rs.getString("status"),
			rs.getInt("attempt_count"),
			rs.getString("last_error_code"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			processed == null ? null : processed.toInstant()
		);
	}
}
