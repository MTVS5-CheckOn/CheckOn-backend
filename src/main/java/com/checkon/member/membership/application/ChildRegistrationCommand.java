package com.checkon.member.membership.application;

/**
 * 자녀 등록 요청 한 건.
 *
 * @param rawBody 🔴 수신한 <b>원문</b>. 역직렬화 후 다시 만든 JSON 이 아니다 —
 *                필드 순서가 달라지면 같은 요청이 멱등 충돌로 판정된다
 */
public record ChildRegistrationCommand(
	String studentPublicId,
	String idempotencyKey,
	String rawBody
) {
}
