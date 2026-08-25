package com.checkon.member.common.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 클라이언트가 준 요청 ID 를 그대로 믿지 않는다는 것을 본다.
 * 개행이 섞인 값이 MDC 를 거쳐 로그에 들어가면 로그 인젝션이 된다.
 */
class RequestIdFilterTest {

	@Test
	@DisplayName("허용 문자로만 된 값은 그대로 echo 한다")
	void echoesValidRequestId() {
		String given = "req-2026.08.25_ABC-123";
		assertThat(RequestIdFilter.sanitize(given)).isEqualTo(given);
	}

	@ParameterizedTest
	@DisplayName("어긋난 값은 서버 생성 값으로 대체한다")
	@ValueSource(strings = {"a\nb", "a b", "한글", "a;rm -rf /", "\t", "a\rb"})
	void replacesSuspiciousRequestId(String given) {
		String sanitized = RequestIdFilter.sanitize(given);

		assertThat(sanitized).isNotEqualTo(given);
		assertThat(UUID.fromString(sanitized)).isNotNull();
	}

	@Test
	@DisplayName("헤더가 없거나 비어 있으면 서버가 만든다")
	void generatesWhenAbsent() {
		assertThat(UUID.fromString(RequestIdFilter.sanitize(null))).isNotNull();
		assertThat(UUID.fromString(RequestIdFilter.sanitize(""))).isNotNull();
	}

	@Test
	@DisplayName("64자를 넘으면 대체한다 — 로그 한 줄을 통째로 밀어내는 값을 막는다")
	void replacesOverlongRequestId() {
		String given = "a".repeat(65);

		String sanitized = RequestIdFilter.sanitize(given);

		assertThat(sanitized).isNotEqualTo(given);
		assertThat(UUID.fromString(sanitized)).isNotNull();
	}
}
