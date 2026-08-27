package com.checkon.publication.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.publication.domain.TeacherConsultationRow;
import com.checkon.publication.domain.TeacherConsultationMessageRow;

/**
 * 강사의 상담 원장 읽기·쓰기. 🔴 <b>강사 컨텍스트가 열린 트랜잭션 안에서만</b> 돈다.
 *
 * <p>🔴 V44 정책이 넷 다 {@code teacher_id = current_checkon_teacher_id()} 다
 * (2026-08-27 실측). 컨텍스트 없이 부르면 예외가 아니라 <b>조용히 0행</b>이고,
 * 남의 상담은 <b>DB 가</b> 안 보여준다 — 애플리케이션 WHERE 절이 아니라 정책이 막는다.</p>
 *
 * <p>🔴 <b>학부모 원문({@code content})을 읽는다.</b> 강사가 답을 쓰려면 질문을 봐야 한다 —
 * 그것이 이 API 의 목적이다. 다만 <b>로그에는 절대 넣지 않는다</b>(PR8 이 그 검사를 만들었다).</p>
 */
@Repository
public class TeacherConsultationRepository {

	private static final String COLUMNS = """
		consultation.id, consultation.parent_id, consultation.student_id,
		consultation.teacher_id, consultation.status, consultation.ai_status,
		consultation.topic, consultation.urgency, consultation.content,
		consultation.created_at, consultation.updated_at,
		consultation.answered_at, consultation.cancelled_at
		""";

	/**
	 * 🔴 {@code WHERE teacher_id = ?} 는 방어가 아니다 — 정책이 이미 격리한다.
	 * 인덱스({@code ix_member_consultations_teacher_status})를 타게 하려고 넣는다.
	 */
	private static final String FIND_PAGE = """
		SELECT %s FROM member_consultations consultation
		WHERE consultation.teacher_id = ?
		  AND (?::text IS NULL OR consultation.status = ?::text)
		  AND (?::timestamptz IS NULL
		       OR (consultation.created_at, consultation.id) < (?::timestamptz, ?::uuid))
		ORDER BY consultation.created_at DESC, consultation.id DESC
		LIMIT ?
		""".formatted(COLUMNS);

	private static final String FIND_ONE = """
		SELECT %s FROM member_consultations consultation
		WHERE consultation.id = ? AND consultation.teacher_id = ?
		""".formatted(COLUMNS);

	/**
	 * 🔴 <b>잠그고 읽는다.</b> 답변 발행은 「상태를 보고 → 메시지를 넣고 → 상태를 바꾼다」라
	 * 그 사이에 다른 요청이 끼면 두 벌이 생긴다. {@code FOR UPDATE} 가 그 창을 없앤다.
	 * {@code SKIP LOCKED} 를 <b>쓰지 않는다</b> — 여기서는 건너뛰면 안 되고, 기다렸다가
	 * 갱신된 상태를 보고 409 를 내야 한다.
	 */
	private static final String LOCK_ONE = FIND_ONE + " FOR UPDATE";

	private static final String FIND_MESSAGES = """
		SELECT id, author_role, content, published_at
		FROM member_consultation_messages
		WHERE consultation_id = ? AND teacher_id = ?
		ORDER BY published_at, id
		""";

	/**
	 * 🔴 {@code published_at} 이 NOT NULL 이다 — <b>이 값을 쓰는 순간이 곧 발행</b>이고,
	 * 미승인 초안은 이 테이블에 물리적으로 못 들어온다. 그 성질을 지키려고 컬럼을 비우는
	 * 경로를 만들지 않는다.
	 *
	 * <p>🔴 INSERT 정책이 {@code author_role = 'TEACHER'} 를 요구한다(V44). 값을 인자로
	 * 받지 않고 리터럴로 고정해 <b>다른 역할로 넣을 방법 자체를 없앤다.</b></p>
	 */
	private static final String INSERT_MESSAGE = """
		INSERT INTO member_consultation_messages
		    (id, consultation_id, parent_id, student_id, teacher_id,
		     author_role, content, published_at, created_at)
		VALUES (?, ?, ?, ?, ?, 'TEACHER', ?, ?, ?)
		""";

	/** 🔴 {@code status} 어휘는 V44 CHECK 그대로다. {@code updated_at >= created_at} 도 지킨다. */
	private static final String MARK_ANSWERED = """
		UPDATE member_consultations
		SET status = 'ANSWERED', answered_at = ?, updated_at = ?
		WHERE id = ? AND teacher_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public TeacherConsultationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<TeacherConsultationRow> findPage(
		UUID teacherId, String status, Instant cursorCreatedAt, UUID cursorId, int limit
	) {
		String cursorAt = cursorCreatedAt == null
			? null : cursorCreatedAt.atOffset(ZoneOffset.UTC).toString();
		String cursorRef = cursorId == null ? null : cursorId.toString();
		return jdbcTemplate.query(FIND_PAGE, TeacherConsultationRepository::map,
			teacherId, status, status, cursorAt, cursorAt, cursorRef, limit);
	}

	public Optional<TeacherConsultationRow> find(UUID consultationId, UUID teacherId) {
		return jdbcTemplate.query(FIND_ONE, TeacherConsultationRepository::map,
			consultationId, teacherId).stream().findFirst();
	}

	/** 🔴 발행 트랜잭션에서만 부른다. 읽기 전용 트랜잭션이면 {@code FOR UPDATE} 가 죽는다. */
	public Optional<TeacherConsultationRow> lock(UUID consultationId, UUID teacherId) {
		return jdbcTemplate.query(LOCK_ONE, TeacherConsultationRepository::map,
			consultationId, teacherId).stream().findFirst();
	}

	public List<TeacherConsultationMessageRow> findMessages(UUID consultationId, UUID teacherId) {
		return jdbcTemplate.query(FIND_MESSAGES, (rs, rowNum) ->
			new TeacherConsultationMessageRow(
				rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
				instant(rs, 4)), consultationId, teacherId);
	}

	/** @param publishedAt 🔴 호출자가 <b>한 번 떠서</b> 넘긴 시각. 여기서 다시 뜨지 않는다 */
	public UUID insertTeacherMessage(TeacherConsultationRow consultation, String content,
		Instant publishedAt) {
		OffsetDateTime at = OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC);
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(INSERT_MESSAGE, id, consultation.consultationId(),
			consultation.parentId(), consultation.studentId(), consultation.teacherId(),
			content, at, at);
		return id;
	}

	/** @return 갱신된 행 수. 🔴 RLS 는 예외가 아니라 0행으로 거절한다 — 호출자가 확인한다 */
	public int markAnswered(UUID consultationId, UUID teacherId, Instant answeredAt) {
		OffsetDateTime at = OffsetDateTime.ofInstant(answeredAt, ZoneOffset.UTC);
		return jdbcTemplate.update(MARK_ANSWERED, at, at, consultationId, teacherId);
	}

	private static TeacherConsultationRow map(ResultSet rs, int rowNum) throws SQLException {
		return new TeacherConsultationRow(
			rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
			rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
			rs.getString(9), instant(rs, 10), instant(rs, 11),
			instant(rs, 12), instant(rs, 13));
	}

	private static Instant instant(ResultSet rs, int index) throws SQLException {
		OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
