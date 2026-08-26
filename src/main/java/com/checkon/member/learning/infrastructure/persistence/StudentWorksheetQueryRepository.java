package com.checkon.member.learning.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberAttemptStatus;
import com.checkon.member.learning.domain.StudentAssignmentPageRow;
import com.checkon.member.learning.domain.StudentAssignmentRow;
import com.checkon.member.learning.domain.StudentAttemptSummary;

/**
 * 학습지 목록·상세의 원자 조회. 🔴 <b>학생 컨텍스트만</b> 요구한다 — {@code problem_assignments}
 * 는 학생 self 정책이(V38:154), {@code member_attempts} 도 학생 self 정책이 있다(V40:210-231).
 *
 * <p>🔴 {@code itemCount} 는 여기서 세지 않는다. {@code saved_problem_set_items} 는
 * {@code current_checkon_scope_problem_set_id()} 를 요구하는 정책이 걸려 있어(V38:176) 한
 * 번에 하나의 set 만 볼 수 있다. 목록의 각 행에 대해 스코프를 여는 것은 서비스의 책임이다
 * (S2 이후). 이 리포지토리는 스코프를 열지 않는다.</p>
 *
 * <p>정렬은 {@code published_at DESC, assignment_id DESC} — 계약 §7-1 목록 규칙과 같다.
 * cursor 파싱은 서비스가 한다.</p>
 */
@Repository
public class StudentWorksheetQueryRepository {

	private static final String FIND_ASSIGNMENTS_BY_STUDENT = """
		SELECT id, problem_set_id, teacher_id, published_at
		FROM problem_assignments
		WHERE student_id = ?
		ORDER BY published_at DESC, id DESC
		""";

	private static final String FIND_ASSIGNMENT_BY_ID = """
		SELECT id, problem_set_id, teacher_id, published_at
		FROM problem_assignments
		WHERE id = ? AND student_id = ?
		""";

	private static final String FIND_ATTEMPT_SUMMARIES = """
		SELECT DISTINCT ON (assignment_id) assignment_id, id, status
		FROM member_attempts
		WHERE student_id = ?
		ORDER BY assignment_id, started_at DESC, id DESC
		""";

	// 🔴 목록 조회의 정본 SQL. student_id 필터는 방어가 아니라 인덱스 힌트다 —
	//    problem_assignments_member_student_select(V38:154) 가 이미 격리한다.
	//    LATERAL 로 member_attempts 학생 self 정책 위에서 자기 최신 attempt 를 얻는다 —
	//    없으면 status/attempt_id 가 NULL 이다. cursor 조건은
	//    (published_at, id) < (cursor_published_at, cursor_assignment_id) 로 표현해
	//    idx_problem_assignments 정렬 인덱스를 그대로 탄다.
	private static final String FIND_ASSIGNMENT_PAGE = """
		SELECT a.id, a.problem_set_id, a.teacher_id, a.published_at,
		       att.id, att.status
		FROM problem_assignments a
		LEFT JOIN LATERAL (
		    SELECT id, status
		    FROM member_attempts m
		    WHERE m.student_id = a.student_id AND m.assignment_id = a.id
		    ORDER BY started_at DESC, id DESC
		    LIMIT 1
		) att ON true
		WHERE a.student_id = ?
		  AND (?::timestamptz IS NULL
		       OR (a.published_at, a.id) < (?::timestamptz, ?::uuid))
		ORDER BY a.published_at DESC, a.id DESC
		LIMIT ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public StudentWorksheetQueryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 목록 한 페이지. limit 은 서비스가 <b>정확히 페이지 크기 + 1</b> 로 넣어 hasNext 를 계산한다.
	 * cursor 가 {@code null} 이면 첫 페이지다.
	 *
	 * @param cursorPublishedAt 다음 페이지의 상한 (열림 구간)
	 * @param cursorAssignmentId 상한 시각과 같은 초의 tie-break
	 * @param limit 페이지 크기 + 1 (서비스가 넘긴다)
	 */
	public List<StudentAssignmentPageRow> findAssignmentPage(
		UUID studentId,
		OffsetDateTime cursorPublishedAt,
		UUID cursorAssignmentId,
		int limit
	) {
		return jdbcTemplate.query(FIND_ASSIGNMENT_PAGE, (rs, rowNum) -> {
			String statusText = rs.getString(6);
			return new StudentAssignmentPageRow(
				rs.getObject(1, UUID.class),
				rs.getObject(2, UUID.class),
				rs.getObject(3, UUID.class),
				rs.getObject(4, OffsetDateTime.class).toInstant(),
				(UUID) rs.getObject(5),
				statusText == null ? null : MemberAttemptStatus.valueOf(statusText)
			);
		}, studentId, cursorPublishedAt, cursorPublishedAt, cursorAssignmentId, limit);
	}

	public List<StudentAssignmentRow> findAssignmentsByStudent(UUID studentId) {
		return jdbcTemplate.query(FIND_ASSIGNMENTS_BY_STUDENT, (rs, rowNum) ->
			new StudentAssignmentRow(
				rs.getObject(1, UUID.class),
				rs.getObject(2, UUID.class),
				rs.getObject(3, UUID.class),
				rs.getObject(4, OffsetDateTime.class).toInstant()
			), studentId);
	}

	public Optional<StudentAssignmentRow> findAssignment(UUID assignmentId, UUID studentId) {
		return jdbcTemplate.query(FIND_ASSIGNMENT_BY_ID, rs -> rs.next()
			? Optional.of(new StudentAssignmentRow(
				rs.getObject(1, UUID.class),
				rs.getObject(2, UUID.class),
				rs.getObject(3, UUID.class),
				rs.getObject(4, OffsetDateTime.class).toInstant()))
			: Optional.<StudentAssignmentRow>empty(), assignmentId, studentId);
	}

	/**
	 * 학생의 assignment 별 <b>최신</b> attempt 요약. 🔴 정확도 계산 원본은 이 PR 에 없다 —
	 * {@code problem_assignment_responses}(승우님 테이블) 에서 채점 후 뽑아 오는 것은 S4 다.
	 * S1 에서는 attempt 상태만 정확히 돌려주고 {@code accuracyRate} 는 항상 {@code null} 이다.
	 */
	public List<StudentAttemptSummary> findLatestAttemptSummaries(UUID studentId) {
		return jdbcTemplate.query(FIND_ATTEMPT_SUMMARIES, (rs, rowNum) ->
			new StudentAttemptSummary(
				rs.getObject(1, UUID.class),
				rs.getObject(2, UUID.class),
				MemberAttemptStatus.valueOf(rs.getString(3)),
				null
			), studentId);
	}
}
