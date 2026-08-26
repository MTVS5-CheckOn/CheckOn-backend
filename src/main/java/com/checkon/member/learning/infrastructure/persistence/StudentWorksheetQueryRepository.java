package com.checkon.member.learning.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberAttemptStatus;
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

	private final JdbcTemplate jdbcTemplate;

	public StudentWorksheetQueryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
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
