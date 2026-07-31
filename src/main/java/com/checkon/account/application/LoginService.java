package com.checkon.account.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountPasswordCredential;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.domain.AuthenticationSession;
import com.checkon.account.domain.EmailAddress;
import com.checkon.account.infrastructure.persistence.AccountPasswordCredentialRepository;
import com.checkon.account.infrastructure.persistence.AccountRepository;
import com.checkon.account.infrastructure.persistence.AuthenticationSessionRepository;
import com.checkon.account.infrastructure.security.AccessTokenService;
import com.checkon.account.infrastructure.security.AccessTokenService.IssuedAccessToken;
import com.checkon.account.infrastructure.security.AuthenticationProperties;
import com.checkon.account.infrastructure.security.RefreshTokenService;
import com.checkon.roster.infrastructure.persistence.TeacherProfileRepository;

/**
 * 자격정보 검증, 세션 생성, 토큰 발급을 하나의 로그인 유스케이스로 묶는다.
 */
@Service
public class LoginService {

	// 존재하지 않는 이메일도 BCrypt 연산을 수행해 계정 존재 여부 시간차를 줄인다.
	private static final String DUMMY_BCRYPT_HASH =
		"$2a$10$7EqJtq98hPqEX7fNZaFWoO5Fn6MZB5Y2PzY9P8BK8ZlLxw5kGJ8mK";

	private final AccountRepository accountRepository;
	private final AccountPasswordCredentialRepository credentialRepository;
	private final AuthenticationSessionRepository sessionRepository;
	private final TeacherProfileRepository teacherProfileRepository;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final AccessTokenService accessTokenService;
	private final AuthenticationProperties properties;
	private final Clock clock;

	public LoginService(
		AccountRepository accountRepository,
		AccountPasswordCredentialRepository credentialRepository,
		AuthenticationSessionRepository sessionRepository,
		TeacherProfileRepository teacherProfileRepository,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokenService,
		AccessTokenService accessTokenService,
		AuthenticationProperties properties,
		Clock clock
	) {
		this.accountRepository = accountRepository;
		this.credentialRepository = credentialRepository;
		this.sessionRepository = sessionRepository;
		this.teacherProfileRepository = teacherProfileRepository;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.accessTokenService = accessTokenService;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public AuthenticationResult login(String rawEmail, String rawPassword) {
		EmailAddress email;
		try {
			email = EmailAddress.of(rawEmail);
		}
		catch (IllegalArgumentException exception) {
			passwordEncoder.matches(safePassword(rawPassword), DUMMY_BCRYPT_HASH);
			throw new InvalidCredentialsException();
		}

		Account account = accountRepository.findByEmail(email.value()).orElse(null);
		AccountPasswordCredential credential = account == null
			? null
			: credentialRepository.findById(account.id()).orElse(null);
		// 존재하지 않는 이메일과 잘못된 비밀번호 모두 같은 BCrypt 비교와
		// 같은 공개 예외를 거치게 해 계정 존재 여부가 응답으로 드러나지 않게 한다.
		String storedHash = credential == null
			? DUMMY_BCRYPT_HASH
			: credential.passwordHash();
		if (rawPassword == null || !passwordEncoder.matches(rawPassword, storedHash)
			|| account == null || credential == null) {
			throw new InvalidCredentialsException();
		}
		if (!account.isActive()) {
			throw new AccountNotActiveException();
		}

		Instant now = Instant.now(clock);
		String refreshToken = refreshTokenService.generate();
		// 세션을 flush해 UUID가 확정된 뒤 그 ID를 JWT의 sid claim에 넣는다.
		// 세션 생성과 last_login_at 변경은 같은 트랜잭션이므로 일부만 남지 않는다.
		AuthenticationSession session = sessionRepository.saveAndFlush(
			AuthenticationSession.issue(
				account,
				refreshTokenService.hash(refreshToken),
				now,
				now.plus(properties.refreshTokenTtl())
			)
		);
		account.recordSuccessfulLogin(now);
		IssuedAccessToken accessToken = accessTokenService.issue(
			account,
			session.id(),
			now
		);
		return result(account, refreshToken, accessToken);
	}

	private AuthenticationResult result(
		Account account,
		String refreshToken,
		IssuedAccessToken accessToken
	) {
		return new AuthenticationResult(
			accessToken.value(),
			accessToken.expiresAt(),
			refreshToken,
			account.id(),
			account.role(),
			account.email(),
			account.role() == AccountRole.TEACHER
				? teacherProfileRepository.findByAccount_Id(account.id())
					.orElseThrow()
					.id()
				: null
		);
	}

	private static String safePassword(String password) {
		return password == null ? "" : password;
	}
}
