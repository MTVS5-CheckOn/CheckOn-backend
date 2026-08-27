package com.checkon.member.learning.application.dto;

import java.util.Map;
import java.util.UUID;

/**
 * {@code PATCH /member/students/me/attempts/{attemptId}/progress} 요청 body.
 * 계약 {@code AttemptProgressRequest} 와 필드 이름·타입이 같다.
 *
 * <p>🔴 {@code baseVersion} 과 {@code clientSequence} 는 필수다. {@code answers} 와
 * {@code activeElapsedSecondsDelta} 는 변경된 항목만 보낸다 — 아무 것도 없어도 요청은
 * 유효하다(사용자가 오래 머무르며 답을 안 골랐을 뿐).</p>
 *
 * <p>🔴 {@code activeElapsedSecondsDelta} 는 <b>항목당 상한 600</b>이다(계약). 초과는 조용히
 * 깎지 않고 400 으로 거절한다.</p>
 */
public record AttemptProgressRequest(
	Integer baseVersion,
	Integer clientSequence,
	UUID currentItemId,
	Map<UUID, Integer> answers,
	Map<UUID, Integer> activeElapsedSecondsDelta
) {
}
