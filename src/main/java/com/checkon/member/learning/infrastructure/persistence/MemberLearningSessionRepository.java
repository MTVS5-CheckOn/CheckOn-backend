package com.checkon.member.learning.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberLearningSession;

/**
 * {@code member_learning_sessions} 저장. 정책은 학생 self, 학부모 scope, 강사 scope 세 방향
 * (V40:352-378) — 요약 데이터라 학부모·강사도 읽는다.
 *
 * <p>제출 트랜잭션 마지막에 한 행 INSERT 로 끝난다(§7 7단계). 이 리포지토리는 update 를 제공하지
 * 않는다 — 요약은 채점 완료 시점에 결정되고 이후 바뀌지 않는다.</p>
 */
@Repository
public class MemberLearningSessionRepository {

	private static final String INSERT = """
		INSERT INTO member_learning_sessions
			(id, attempt_id, student_id, teacher_id, assignment_id, title_text,
			 item_count, correct_count, active_elapsed_sec, submit_record_id, occurred_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String FIND_BY_ATTEMPT = """
		SELECT id, attempt_id, student_id, teacher_id, assignment_id, title_text,
		       item_count, correct_count, active_elapsed_sec, submit_record_id, occurred_at
		FROM member_learning_sessions
		WHERE attempt_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberLearningSessionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(MemberLearningSession session) {
		jdbcTemplate.update(INSERT,
			session.id(),
			session.attemptId(),
			session.studentId(),
			session.teacherId(),
			session.assignmentId(),
			session.titleText(),
			session.itemCount(),
			session.correctCount(),
			session.activeElapsedSec(),
			session.submitRecordId(),
			OffsetDateTime.ofInstant(session.occurredAt(), ZoneOffset.UTC));
	}

	private static final String COLUMNS =
		"id, attempt_id, student_id, teacher_id, assignment_id, title_text,"
			+ " item_count, correct_count, active_elapsed_sec, submit_record_id, occurred_at";

	// 🔴 cursor = (occurred_at, id) 열림 구간, DESC 순서. month 필터가 있으면 [from, to) UTC.
	private static final String FIND_PAGE = """
		SELECT %s FROM member_learning_sessions
		WHERE student_id = ?
		  AND (?::timestamptz IS NULL OR occurred_at >= ?)
		  AND (?::timestamptz IS NULL OR occurred_at < ?)
		  AND (?::timestamptz IS NULL
		       OR (occurred_at, id) < (?::timestamptz, ?::uuid))
		ORDER BY occurred_at DESC, id DESC
		LIMIT ?
		""".formatted(COLUMNS);

	private static final String FIND_BY_ID = """
		SELECT %s FROM member_learning_sessions
		WHERE id = ? AND student_id = ?
		""".formatted(COLUMNS);

	public Optional<MemberLearningSession> findByAttempt(UUID attemptId) {
		return jdbcTemplate.query(FIND_BY_ATTEMPT, resultSet -> resultSet.next()
			? Optional.of(map(resultSet))
			: Optional.empty(), attemptId);
	}

	public Optional<MemberLearningSession> findById(UUID sessionId, UUID studentId) {
		return jdbcTemplate.query(FIND_BY_ID, resultSet -> resultSet.next()
			? Optional.of(map(resultSet))
			: Optional.empty(), sessionId, studentId);
	}

	/**
	 * 학습기록 목록.
	 *
	 * @param studentId  대상 학생
	 * @param fromInclusive month 필터 시작 (nullable)
	 * @param toExclusive   month 필터 종료 (nullable)
	 * @param cursorOccurredAt cursor 시각 (nullable — 첫 페이지면 null)
	 * @param cursorId         cursor id (nullable — 첫 페이지면 null)
	 * @param limit      상한
	 */
	public List<MemberLearningSession> findPage(
		UUID studentId,
		Instant fromInclusive,
		Instant toExclusive,
		Instant cursorOccurredAt,
		UUID cursorId,
		int limit
	) {
		OffsetDateTime from = fromInclusive == null ? null
			: OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC);
		OffsetDateTime to = toExclusive == null ? null
			: OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC);
		OffsetDateTime cursor = cursorOccurredAt == null ? null
			: OffsetDateTime.ofInstant(cursorOccurredAt, ZoneOffset.UTC);
		return jdbcTemplate.query(FIND_PAGE,
			(rs, rowNum) -> map(rs),
			studentId,
			from, from,
			to, to,
			cursor, cursor, cursorId,
			limit);
	}

	private static MemberLearningSession map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new MemberLearningSession(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getObject(4, UUID.class),
			rs.getObject(5, UUID.class),
			rs.getString(6),
			rs.getInt(7),
			rs.getInt(8),
			rs.getInt(9),
			rs.getObject(10, UUID.class),
			rs.getObject(11, OffsetDateTime.class).toInstant());
	}
}
