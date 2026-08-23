package com.checkon.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class AuthenticationPropertiesTest {

	@Test
	void allowsSecureCrossSiteRefreshCookie() {
		AuthenticationProperties properties = properties(
			true,
			"none",
			List.of("https://checkon-front.vercel.app")
		);

		assertThat(properties.refreshCookieSameSite()).isEqualTo("None");
		assertThat(properties.refreshCookieSecure()).isTrue();
	}

	@Test
	void rejectsSameSiteNoneWithoutSecure() {
		assertThatThrownBy(() -> properties(
			false,
			"None",
			List.of("https://checkon-front.vercel.app")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("refresh-cookie-secure=true");
	}

	@Test
	void normalizesSameSiteAndOriginWhitespaceAndRemovesDuplicates() {
		AuthenticationProperties properties = properties(
			false,
			" lax ",
			List.of(" http://localhost:3000 ", "http://localhost:3000")
		);

		assertThat(properties.refreshCookieSameSite()).isEqualTo("Lax");
		assertThat(properties.allowedOrigins()).containsExactly("http://localhost:3000");
	}

	@Test
	void rejectsUnsupportedSameSite() {
		assertThatThrownBy(() -> properties(true, "SameOrigin", List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SameOrigin");
	}

	@Test
	void rejectsWildcardAndNonHttpOrigins() {
		assertThatThrownBy(() -> properties(true, "Lax", List.of("*")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("http or https");
		assertThatThrownBy(() -> properties(true, "Lax", List.of("file://localhost")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("http or https");
	}

	@Test
	void rejectsOriginWithPathQueryFragmentOrTrailingSlash() {
		assertInvalidOrigin("https://front.example/");
		assertInvalidOrigin("https://front.example/app");
		assertInvalidOrigin("https://front.example?tenant=one");
		assertInvalidOrigin("https://front.example#fragment");
	}

	@Test
	void keepsEmptyAllowListAsSafeSameOriginDefault() {
		assertThat(properties(true, "Lax", null).allowedOrigins()).isEmpty();
		assertThat(properties(true, "Lax", List.of("")).allowedOrigins()).isEmpty();
	}

	private void assertInvalidOrigin(String origin) {
		assertThatThrownBy(() -> properties(true, "Lax", List.of(origin)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid allowed origin");
	}

	private AuthenticationProperties properties(
		boolean secure,
		String sameSite,
		List<String> origins
	) {
		return new AuthenticationProperties(
			"dGVzdC1vbmx5LWtleS1tdXN0LW5ldmVyLWJlLXVzZWQtaW4tcHJvZA==",
			Duration.ofMinutes(15),
			Duration.ofDays(14),
			"CHECKON_REFRESH",
			secure,
			sameSite,
			origins
		);
	}
}
