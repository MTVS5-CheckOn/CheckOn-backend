package com.checkon.member.learning.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 학습지 목록 조립의 원자 단위. {@code problem_assignments} 한 행을 학생 컨텍스트로 조회해서
 * 나온다({@code problem_assignments_member_student_select}, V38:154).
 *
 * <p>🔴 여기에는 {@code title}·{@code itemCount}·{@code teacherDisplayName} 이 없다 — 각각
 * 파생·별도 조회·별도 조회 대상이다. 목록 응답은 서비스가 여러 조각을 합쳐 만든다.</p>
 */
public record StudentAssignmentRow(
	UUID assignmentId,
	UUID problemSetId,
	UUID teacherId,
	Instant publishedAt
) {
}
