package com.checkon.member.integration.account;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.checkon.account.application.AccountNotActiveException;
import com.checkon.account.application.AuthenticationResult;
import com.checkon.account.application.InvalidCredentialsException;
import com.checkon.account.application.LoginService;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticationProperties;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.security.MemberRole;

/**
 * 기존 {@code LoginService} 를 member 경계에서 재사용한다.
 *
 * <p>member 에서 {@code com.checkon.account} 를 import 하는 곳은 {@code integration/} 아래뿐이다
 * (코드 규칙 G2 는 파일 이름이 아니라 <b>경로에 {@code /integration/} 이 있는가</b>로 판정한다).</p>
 *
 * <p>🔴 refresh·logout 은 만들지 않는다. refresh 쿠키 Path 가
 * {@code AuthenticationController} 의 상수로 하드코딩돼 있어 새로 만들면 팀원 파일을 고쳐야 한다.
 * 학생 login 만 신설할 수 있는 이유는 쿠키를 <b>발급만</b> 하면 되고, 그 Path 값을 상수에서
 * 읽는 게 아니라 같은 문자열을 쓰면 되기 때문이다.</p>
 */
@Component
public class MemberLoginAdapter {

	/**
	 * 🔴 기존 {@code POST /api/v1/auth/refresh} 가 이 쿠키를 읽어야 한다.
	 * member 전용 Path 를 쓰면 갱신이 죽는다.
	 */
	private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

	/**
	 * 🔴 공개 ID 열거 방어용 더미 해시. 계정을 못 찾았을 때도 BCrypt 시간을 태워
	 * "없는 ID"와 "비밀번호 틀림"의 응답 시간을 구분할 수 없게 만든다.
	 * 값은 어떤 계정과도 매칭되지 않는다.
	 */
	private static final String DUMMY_HASH =
		"$2a$10$C6UzMDM.H6dfI/f/IKcEe.3/RXR8dLDgKkVGkNjVc4hOZBs2dFVJa";

	private final LoginService loginService;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationProperties properties;

	public MemberLoginAdapter(
		LoginService loginService,
		PasswordEncoder passwordEncoder,
		AuthenticationProperties properties
	) {
		this.loginService = loginService;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
	}

	/**
	 * 🔴 예외 번역도 여기서 한다. 호출부가 {@code InvalidCredentialsException} 을 잡으려면
	 * 남의 패키지를 import 해야 하고 그건 G2 위반이다.
	 *
	 * <p>🔴 {@code RuntimeException} 을 통째로 잡지 않는다 — 잡으면 DB 장애가 500 이 아니라
	 * 401 로 나가서 장애가 로그인 실패로 위장된다.</p>
	 */
	public MemberAuthentication login(String email, String rawPassword) {
		AuthenticationResult result;
		try {
			result = loginService.login(email, rawPassword);
		}
		catch (InvalidCredentialsException exception) {
			throw new MemberException(
				MemberErrorCode.INVALID_CREDENTIALS, "password does not match");
		}
		catch (AccountNotActiveException exception) {
			// 계약 member-api.yaml:153 이 별도 코드로 못 박았다. 여기 도달했다는 건
			// 이미 비밀번호가 맞았다는 뜻이라 열거 위험이 없다.
			throw new MemberException(MemberErrorCode.ACCOUNT_NOT_ACTIVE, "account is not active");
		}
		return new MemberAuthentication(
			result.accessToken(),
			result.accessTokenExpiresAt(),
			result.refreshToken(),
			result.accountId(),
			result.role() == AccountRole.STUDENT ? MemberRole.STUDENT : MemberRole.PARENT,
			result.email());
	}

	/**
	 * 계정을 찾지 못했을 때 부른다. BCrypt 시간을 태우기만 하고 결과는 버린다.
	 *
	 * <p>🔴 여기서 즉시 반환하면 응답 시간 차로 공개 ID 존재 여부가 새어나간다.</p>
	 */
	public void burnPasswordComparisonTime(String rawPassword) {
		passwordEncoder.matches(rawPassword == null ? "" : rawPassword, DUMMY_HASH);
	}

	/** 🔴 쿠키 이름·Secure·SameSite·TTL 을 하드코딩하지 않는다. 설정에서 가져온다. */
	public ResponseCookie refreshCookie(String rawRefreshToken) {
		Duration ttl = properties.refreshTokenTtl();
		return ResponseCookie.from(properties.refreshCookieName(), rawRefreshToken)
			.httpOnly(true)
			.secure(properties.refreshCookieSecure())
			.sameSite(properties.refreshCookieSameSite())
			.path(REFRESH_COOKIE_PATH)
			.maxAge(ttl)
			.build();
	}

}
