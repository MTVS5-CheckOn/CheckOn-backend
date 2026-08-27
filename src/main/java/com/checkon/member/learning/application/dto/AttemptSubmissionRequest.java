package com.checkon.member.learning.application.dto;

import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /member/students/me/attempts/{attemptId}/submission} 요청 body.
 *
 * <p>🔴 {@code baseVersion} 필수. 낙관락 충돌은 409 REVISION_CONFLICT.
 * 🔴 {@code answers} 와 {@code activeElapsedSecondsDelta} 가 있으면 <b>같은 트랜잭션에서</b>
 * 마지막으로 반영된 뒤 채점한다 — progress 를 못 보낸 마지막 변경을 이 필드로 회수한다.
 * 없으면 저장된 답안·시간으로 채점한다.</p>
 *
 * <p>🔴 {@code activeElapsedSecondsDelta} 는 progress 와 <b>같은 규칙</b>이다. 항목당 상한
 * ({@code member.attempt.max-progress-delta-seconds}, 기본 600) 초과는 <b>400</b> 이며 조용히
 * clamp 하지 않는다(설계 §8-1 · 분기표 §1 progress 시간 이상치).</p>
 */
public record AttemptSubmissionRequest(
	Integer baseVersion,
	Map<UUID, Integer> answers,
	Map<UUID, Integer> activeElapsedSecondsDelta
) {
}
