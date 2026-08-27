package com.checkon.member.question.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.question.domain.QuestionAuthorRole;
import com.checkon.member.question.domain.QuestionMessageRecord;
import com.checkon.member.question.domain.QuestionRecord;
import com.checkon.member.question.domain.QuestionStatus;

/**
 * {@code member_questions} · {@code member_question_messages} 접근.
 *
 * <p>🔴 학생 컨텍스트가 필수다 — RLS 없이 쓰면 조용히 0행이다. 서비스가 트랜잭션 시작에
 * {@link com.checkon.member.common.persistence.MemberDatabaseContext#setCurrentStudent} 를
 * 부른다.</p>
 *
 * <p>🔴 attempt·item verify 는 서비스가 다른 리포지토리로 한다 — 여기서 EXISTS 로 얽지 않는다.</p>
 */
@Repository
public class MemberQuestionRepository {

	private static final String Q_COLUMNS =
		"id, student_id, teacher_id, assignment_id, attempt_id, item_id, "
			+ "title, content, status, follow_up_count, created_at, answered_at";

	private static final String INSERT_QUESTION = """
		INSERT INTO member_questions
			(student_id, teacher_id, assignment_id, attempt_id, item_id,
			 title, content, status, follow_up_count, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING', 0, ?)
		RETURNING id
		""";

	private static final String FIND_BY_ID = """
		SELECT %s FROM member_questions WHERE id = ?
		""".formatted(Q_COLUMNS);

	// 🔴 목록 — created_at DESC, id DESC. cursor 는 (published/created_at, id) 열림 구간.
	private static final String FIND_PAGE = """
		SELECT %s FROM member_questions
		WHERE student_id = ?
		  AND (?::timestamptz IS NULL
		       OR (created_at, id) < (?::timestamptz, ?::uuid))
		ORDER BY created_at DESC, id DESC
		LIMIT ?
		""".formatted(Q_COLUMNS);

	private static final String INSERT_MESSAGE = """
		INSERT INTO member_question_messages
			(question_id, author_role, author_account_id, content, published_at)
		VALUES (?, ?, ?, ?, ?)
		RETURNING id
		""";

	private static final String FIND_MESSAGES = """
		SELECT id, question_id, author_role, author_account_id, content, published_at
		FROM member_question_messages
		WHERE question_id = ?
		ORDER BY published_at ASC, id ASC
		""";

	// 🔴 후속 질문. follow_up_count 를 원자적으로 +1 하고 최신값을 돌려준다.
	private static final String INCREMENT_FOLLOW_UP = """
		UPDATE member_questions
		SET status = 'FOLLOW_UP', follow_up_count = follow_up_count + 1
		WHERE id = ?
		RETURNING follow_up_count
		""";

	// 🔴 assignment 소유·teacher 확인용 — RLS 학생 self 정책이 격리한다. 남의 assignment 는 0행.
	//    🔴 StudentWorksheetQueryRepository.findAssignment 와 달리 EXISTS 관계 필터를 <b>넣지
	//    않는다.</b> 질문 서비스는 관계 부재를 422 RELATIONSHIP_REQUIRED 로 갈라야 하므로
	//    (관계 필터를 여기 넣으면 404 로 뭉개진다) — 별도 쿼리다.
	private static final String FIND_ASSIGNMENT_TEACHER = """
		SELECT teacher_id FROM problem_assignments
		WHERE id = ? AND student_id = ?
		""";

	// 🔴 attempt 소유·assignment 매칭 확인용. RLS 정책이 attempt 를 학생 self 로 격리하므로
	//    남의 attempt 는 여기서 0행이다.
	private static final String FIND_ATTEMPT_ASSIGNMENT = """
		SELECT assignment_id FROM member_attempts
		WHERE id = ? AND student_id = ?
		""";

	// 🔴 attempt 안의 문항 ordinal. 없으면 0행.
	private static final String FIND_ITEM_ORDINAL = """
		SELECT ordinal FROM member_attempt_items
		WHERE attempt_id = ? AND item_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberQuestionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public UUID insertQuestion(QuestionRecord newRow) {
		return jdbcTemplate.queryForObject(INSERT_QUESTION, UUID.class,
			newRow.studentId(),
			newRow.teacherId(),
			newRow.assignmentId(),
			newRow.attemptId(),
			newRow.itemId(),
			newRow.title(),
			newRow.content(),
			offset(newRow.createdAt()));
	}

	public Optional<QuestionRecord> findById(UUID id) {
		return jdbcTemplate.query(FIND_BY_ID, rs -> rs.next()
			? Optional.of(map(rs))
			: Optional.<QuestionRecord>empty(), id);
	}

	public List<QuestionRecord> findPage(
		UUID studentId, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit
	) {
		return jdbcTemplate.query(FIND_PAGE, (rs, rowNum) -> map(rs),
			studentId, cursorCreatedAt, cursorCreatedAt, cursorId, limit);
	}

	public UUID insertMessage(
		UUID questionId, QuestionAuthorRole role, UUID authorAccountId,
		String content, Instant publishedAt
	) {
		return jdbcTemplate.queryForObject(INSERT_MESSAGE, UUID.class,
			questionId, role.name(), authorAccountId, content, offset(publishedAt));
	}

	public List<QuestionMessageRecord> findMessages(UUID questionId) {
		return jdbcTemplate.query(FIND_MESSAGES, (rs, rowNum) -> new QuestionMessageRecord(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			QuestionAuthorRole.valueOf(rs.getString(3)),
			rs.getObject(4, UUID.class),
			rs.getString(5),
			rs.getObject(6, OffsetDateTime.class).toInstant()
		), questionId);
	}

	public int incrementFollowUp(UUID id) {
		Integer newCount = jdbcTemplate.queryForObject(INCREMENT_FOLLOW_UP, Integer.class, id);
		return newCount == null ? 0 : newCount;
	}

	/** assignment 의 teacher_id (자기 assignment 만). */
	public Optional<UUID> findAssignmentTeacher(UUID assignmentId, UUID studentId) {
		return jdbcTemplate.query(FIND_ASSIGNMENT_TEACHER, rs -> rs.next()
			? Optional.of(rs.getObject(1, UUID.class))
			: Optional.<UUID>empty(), assignmentId, studentId);
	}

	/** attempt 소유·assignment 매칭 확인. 없으면 empty. */
	public Optional<UUID> findAttemptAssignment(UUID attemptId, UUID studentId) {
		return jdbcTemplate.query(FIND_ATTEMPT_ASSIGNMENT, rs -> rs.next()
			? Optional.of(rs.getObject(1, UUID.class))
			: Optional.<UUID>empty(), attemptId, studentId);
	}

	/** attempt 안 문항의 ordinal. 없으면 empty. */
	public Optional<Integer> findItemOrdinal(UUID attemptId, UUID itemId) {
		return jdbcTemplate.query(FIND_ITEM_ORDINAL, rs -> rs.next()
			? Optional.of(rs.getInt(1))
			: Optional.<Integer>empty(), attemptId, itemId);
	}

	private static QuestionRecord map(ResultSet rs) throws java.sql.SQLException {
		return new QuestionRecord(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getObject(4, UUID.class),
			(UUID) rs.getObject(5),
			(UUID) rs.getObject(6),
			rs.getString(7),
			rs.getString(8),
			QuestionStatus.valueOf(rs.getString(9)),
			rs.getInt(10),
			rs.getObject(11, OffsetDateTime.class).toInstant(),
			instant(rs.getObject(12, OffsetDateTime.class))
		);
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime offset(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}
}
