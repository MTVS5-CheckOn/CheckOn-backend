package com.checkon.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.checkon.account.application.AuthenticationResult;
import com.checkon.account.application.LoginService;
import com.checkon.account.application.LogoutService;
import com.checkon.account.application.RefreshAuthenticationService;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticationProperties;

class AuthenticationControllerCookieTest {

	@Test
	void writesSecureSameSiteNoneCookieForCrossSiteDeployment() {
		LoginService loginService = mock(LoginService.class);
		AuthenticationProperties properties = new AuthenticationProperties(
			"dGVzdC1vbmx5LWtleS1tdXN0LW5ldmVyLWJlLXVzZWQtaW4tcHJvZA==",
			Duration.ofMinutes(15),
			Duration.ofDays(14),
			"CHECKON_REFRESH",
			true,
			"None",
			List.of("https://checkon-front.vercel.app")
		);
		AuthenticationResult result = new AuthenticationResult(
			"access-token",
			Instant.parse("2026-08-23T12:00:00Z"),
			"refresh-token",
			UUID.fromString("0198f000-0000-7000-8000-000000000000"),
			AccountRole.TEACHER,
			"teacher@example.com",
			UUID.fromString("0198f000-0000-7000-8000-000000000001")
		);
		when(loginService.login("teacher@example.com", "password")).thenReturn(result);
		AuthenticationController controller = new AuthenticationController(
			loginService,
			mock(RefreshAuthenticationService.class),
			mock(LogoutService.class),
			properties
		);

		String setCookie = controller.login(
			new AuthenticationController.LoginRequest("teacher@example.com", "password")
		).getHeaders().getFirst(HttpHeaders.SET_COOKIE);

		assertThat(setCookie)
			.contains("CHECKON_REFRESH=refresh-token")
			.contains("Path=/api/v1/auth")
			.contains("Secure")
			.contains("HttpOnly")
			.contains("SameSite=None");
	}
}
