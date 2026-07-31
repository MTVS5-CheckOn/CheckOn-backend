package com.checkon.account.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.domain.AuthenticationSession;
import com.checkon.account.infrastructure.persistence.AuthenticationSessionRepository;
import com.checkon.account.infrastructure.security.AccessTokenService;
import com.checkon.account.infrastructure.security.AccessTokenService.IssuedAccessToken;
import com.checkon.account.infrastructure.security.RefreshTokenService;
import com.checkon.roster.infrastructure.persistence.TeacherProfileRepository;

/**
 * Refresh 검증과 해시 교체를 같은 트랜잭션/행 잠금 안에서 처리한다.
 */
@Service
public class RefreshAuthenticationService {

	private final AuthenticationSessionRepository sessionRepository;
	private final TeacherProfileRepository teacherProfileRepository;
	private final RefreshTokenService refreshTokenService;
	private final AccessTokenService accessTokenService;
	private final Clock clock;

	public RefreshAuthenticationService(
		AuthenticationSessionRepository sessionRepository,
		TeacherProfileRepository teacherProfileRepository,
		RefreshTokenService refreshTokenService,
		AccessTokenService accessTokenService,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.teacherProfileRepository = teacherProfileRepository;
		this.refreshTokenService = refreshTokenService;
		this.accessTokenService = accessTokenService;
		this.clock = clock;
	}

	@Transactional
	public AuthenticationResult refresh(String rawRefreshToken) {
		String currentHash;
		try {
			currentHash = refreshTokenService.hash(rawRefreshToken);
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidSessionException();
		}
		AuthenticationSession session = sessionRepository
			// 해시 조회와 비관적 잠금을 함께 사용한다. 두 요청이 같은 원문 토큰을
			// 동시에 보내도 먼저 잠금을 획득한 요청만 해시를 교체할 수 있다.
			.findByRefreshTokenHashForUpdate(currentHash)
			.orElseThrow(InvalidSessionException::new);
		Account account = session.account();
		if (!account.isActive()) {
			throw new AccountNotActiveException();
		}

		Instant now = Instant.now(clock);
		String newRefreshToken = refreshTokenService.generate();
		try {
			// 새 원문은 이 유스케이스의 반환값으로만 전달하고 엔티티에는 해시만 넣는다.
			// 회전은 세션의 최초 expiresAt을 연장하지 않는 절대 만료 정책이다.
			session.rotate(refreshTokenService.hash(newRefreshToken), now);
		}
		catch (IllegalStateException exception) {
			throw new InvalidSessionException();
		}
		IssuedAccessToken accessToken = accessTokenService.issue(
			account,
			session.id(),
			now
		);
		return new AuthenticationResult(
			accessToken.value(),
			accessToken.expiresAt(),
			newRefreshToken,
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
}
