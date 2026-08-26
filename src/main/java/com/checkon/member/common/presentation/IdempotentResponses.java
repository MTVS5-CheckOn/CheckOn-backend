package com.checkon.member.common.presentation;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.checkon.member.common.persistence.IdempotentOutcome;

/**
 * 멱등 처리 결과를 HTTP 응답으로 옮긴다.
 *
 * <p>🔴 본문을 <b>문자열 그대로</b> 내려보낸다. 저장된 JSON 을 객체로 되돌렸다가 다시 만들면
 * 필드 순서가 흔들려 "같은 key 는 같은 응답"이라는 계약(§0-4)이 바이트 수준에서 깨진다.
 * 최초 응답도 같은 경로를 타므로 두 응답은 <b>구성상</b> 동일하다.</p>
 */
public final class IdempotentResponses {

	private IdempotentResponses() {
	}

	public static ResponseEntity<String> of(IdempotentOutcome outcome) {
		return ResponseEntity.status(outcome.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(outcome.body());
	}
}
