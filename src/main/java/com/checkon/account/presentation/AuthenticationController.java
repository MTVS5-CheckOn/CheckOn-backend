package com.checkon.account.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.application.AuthenticationResult;
import com.checkon.account.application.LoginService;
import com.checkon.account.application.LogoutService;
import com.checkon.account.application.RefreshAuthenticationService;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.account.infrastructure.security.AuthenticationProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인·갱신·현재 세션 로그아웃의 HTTP 변환만 담당한다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

	private static final String COOKIE_PATH = "/api/v1/auth";

	private final LoginService loginService;
	private final RefreshAuthenticationService refreshService;
	private final LogoutService logoutService;
	private final AuthenticationProperties properties;

	public AuthenticationController(
		LoginService loginService,
		RefreshAuthenticationService refreshService,
		LogoutService logoutService,
		AuthenticationProperties properties
	) {
		this.loginService = loginService;
		this.refreshService = refreshService;
		this.logoutService = logoutService;
		this.properties = properties;
	}

	@PostMapping("/login")
	public ResponseEntity<AuthenticationResponse> login(
		@Valid @RequestBody LoginRequest request
	) {
		return authenticationResponse(
			loginService.login(request.email(), request.password())
		);
	}

	@PostMapping("/refresh")
	public ResponseEntity<AuthenticationResponse> refresh(
		HttpServletRequest request
	) {
		String refreshToken = findRefreshCookie(request);
		return authenticationResponse(refreshService.refresh(refreshToken));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
		@AuthenticationPrincipal AuthenticatedAccount principal
	) {
		logoutService.logout(principal.accountId(), principal.sessionId());
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
			.build();
	}

	private ResponseEntity<AuthenticationResponse> authenticationResponse(
		AuthenticationResult result
	) {
		// Access Token은 명시적으로 사용하는 응답 본문에, 브라우저가 직접 읽을
		// 필요가 없는 Refresh Token은 HttpOnly 쿠키에만 나누어 전달한다.
		return ResponseEntity.ok()
			.header(
				HttpHeaders.SET_COOKIE,
				refreshCookie(result.refreshToken()).toString()
			)
			.body(new AuthenticationResponse(new AuthenticationData(
				result.accessToken(),
				result.accessTokenExpiresAt(),
				new AccountData(
					result.accountId(),
					result.role(),
					result.email(),
					result.teacherProfileId()
				)
			)));
	}

	private String findRefreshCookie(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		return Arrays.stream(request.getCookies())
			.filter(cookie -> properties.refreshCookieName().equals(cookie.getName()))
			.map(Cookie::getValue)
			.findFirst()
			.orElse(null);
	}

	private ResponseCookie refreshCookie(String value) {
		// Path를 인증 API로 좁혀 다른 업무 API 요청에 Refresh Token이 실리지 않게 한다.
		return ResponseCookie.from(properties.refreshCookieName(), value)
			.httpOnly(true)
			.secure(properties.refreshCookieSecure())
			.sameSite(properties.refreshCookieSameSite())
			.path(COOKIE_PATH)
			.maxAge(properties.refreshTokenTtl())
			.build();
	}

	private ResponseCookie expiredRefreshCookie() {
		return ResponseCookie.from(properties.refreshCookieName(), "")
			.httpOnly(true)
			.secure(properties.refreshCookieSecure())
			.sameSite(properties.refreshCookieSameSite())
			.path(COOKIE_PATH)
			.maxAge(Duration.ZERO)
			.build();
	}

	public record LoginRequest(
		@NotBlank @Size(max = 320) String email,
		@NotBlank @Size(max = 200) String password
	) {
	}

	public record AuthenticationResponse(AuthenticationData data) {
	}

	public record AuthenticationData(
		String accessToken,
		Instant accessTokenExpiresAt,
		AccountData account
	) {
	}

	public record AccountData(
		UUID id,
		AccountRole role,
		String email,
		UUID teacherProfileId
	) {
	}
}
