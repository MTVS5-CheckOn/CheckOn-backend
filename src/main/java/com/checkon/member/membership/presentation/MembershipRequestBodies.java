package com.checkon.member.membership.presentation;

import java.util.List;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 요청 본문을 <b>원문 문자열에서</b> 읽는다.
 *
 * <p>🔴 왜 {@code @RequestBody} 로 바로 record 를 받지 않나 — 멱등 해시는 <b>수신한 원문 바이트</b>로
 * 만들어야 한다(설계 §12-1). Spring 이 역직렬화한 객체를 다시 직렬화해 해싱하면 필드 순서·공백에
 * 따라 값이 흔들려, 같은 요청이 {@code IDEMPOTENCY_CONFLICT} 로 거절된다. 그래서 본문을
 * 문자열로 받고 여기서 한 번만 파싱한다.</p>
 */
public final class MembershipRequestBodies {

	private MembershipRequestBodies() {
	}

	public static <T> T parse(ObjectMapper objectMapper, String rawBody, Class<T> type) {
		if (rawBody == null || rawBody.isBlank()) {
			throw invalid("body", "request body is required");
		}
		try {
			T parsed = objectMapper.readValue(rawBody, type);
			if (parsed == null) {
				throw invalid("body", "request body is required");
			}
			return parsed;
		}
		catch (JacksonException exception) {
			// 🔴 파서 메시지에는 페이로드 조각이 섞인다. 그대로 내려보내지 않는다.
			throw invalid("body", "request body is not readable");
		}
	}

	/** 🔴 값이 없으면 400 이다. 조용히 null 로 흘려보내면 아래에서 404 로 위장된다. */
	public static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw invalid(field, field + " is required");
		}
		return value;
	}

	private static MemberException invalid(String field, String message) {
		return new MemberException(MemberErrorCode.INVALID_REQUEST, message,
			List.of(new FieldViolation(field, message)));
	}
}
