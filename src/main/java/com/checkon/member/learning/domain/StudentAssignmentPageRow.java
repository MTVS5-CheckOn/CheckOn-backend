package com.checkon.member.learning.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 학습지 목록 한 행 + 최신 attempt 요약. {@code problem_assignments} 학생 self SELECT 와
 * {@code member_attempts} 학생 self SELECT 를 LATERAL 조인으로 한 번에 얻어 왕복을 아낀다.
 *
 * <p>🔴 {@code latestAttemptId} · {@code latestAttemptStatus} 는 attempt 가 아직 없으면 둘 다
 * {@code null} 이다. 서비스가 이 셋을 조합해 계약의 {@code NEW} · {@code IN_PROGRESS} ·
 * {@code COMPLETED} 로 파생한다({@code WorksheetStatuses}).</p>
 *
 * <p>🔴 {@code itemCount} 는 여기 없다. {@code saved_problem_set_items} 정책이
 * {@code current_checkon_scope_problem_set_id()} 를 요구해(V38:176) 한 쿼리로는 못 센다 —
 * 서비스가 행마다 스코프를 열어 센다(MB-39).</p>
 */
public record StudentAssignmentPageRow(
	UUID assignmentId,
	UUID problemSetId,
	UUID teacherId,
	Instant publishedAt,
	UUID latestAttemptId,
	MemberAttemptStatus latestAttemptStatus
) {
}
