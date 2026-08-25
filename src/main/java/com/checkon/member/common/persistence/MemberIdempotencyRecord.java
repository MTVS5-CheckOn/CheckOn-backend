package com.checkon.member.common.persistence;

/**
 * 저장된 멱등 응답.
 *
 * @param requestHash  {@code sha256:<64hex>}. 같은 key 로 다른 본문이 오면 충돌이다
 * @param responseStatus 최초 처리의 HTTP 상태. 재생 시 이 값을 그대로 쓴다
 * @param responseBody 최초 응답의 JSON 원문. 🔴 재직렬화하지 않고 <b>그대로</b> 돌려준다 —
 *                     다시 만들면 필드 순서가 흔들려 "같은 응답"이라는 계약이 깨진다
 */
public record MemberIdempotencyRecord(
	String requestHash,
	int responseStatus,
	String responseBody
) {
}
