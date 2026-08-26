package com.checkon.member.common.presentation;

import java.util.List;
import java.util.UUID;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;

/**
 * {@code Idempotency-Key} 헤더 검증. 분기표 §0-4 의 "key 없음(필수인데) → 400" 을 한 곳에 둔다.
 *
 * <p>🔴 판정을 컨트롤러마다 적으면 새 엔드포인트에서 빠뜨린다 — 그러면 멱등이 조용히 꺼진다.</p>
 */
public final class IdempotencyKeys {

	public static final String HEADER = "Idempotency-Key";

	private IdempotencyKeys() {
	}

	/** @return 원문 그대로의 key. 🔴 정규화하지 않는다 — 저장·비교가 같은 문자열이어야 한다 */
	public static String require(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw invalid("Idempotency-Key header is required");
		}
		try {
			// 계약이 UUID 형식을 요구한다. 형식 자체를 여기서 막아 저장소에 쓰레기가 쌓이지 않게 한다.
			UUID.fromString(rawKey);
		}
		catch (IllegalArgumentException exception) {
			throw invalid("Idempotency-Key must be a UUID");
		}
		return rawKey;
	}

	private static MemberException invalid(String message) {
		return new MemberException(MemberErrorCode.INVALID_REQUEST, message,
			List.of(new FieldViolation(HEADER, message)));
	}
}
