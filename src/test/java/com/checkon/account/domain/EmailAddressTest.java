package com.checkon.account.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로그인 식별자가 저장·비교 전에 항상 같은 형태가 되는지 검증한다.
 */
class EmailAddressTest {

	@Test
	void normalizesWhitespaceAndCase() {
		EmailAddress email = EmailAddress.of(" Teacher@Example.COM ");

		assertThat(email.value()).isEqualTo("teacher@example.com");
		assertThat(email).isEqualTo(EmailAddress.of("teacher@example.com"));
	}

	@Test
	void rejectsNullBlankMalformedAndTooLongValues() {
		assertThatThrownBy(() -> EmailAddress.of(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EmailAddress.of("   "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EmailAddress.of("not-an-email"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EmailAddress.of("a".repeat(310) + "@example.com"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
