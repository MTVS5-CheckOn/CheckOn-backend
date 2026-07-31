package com.checkon.account.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.domain.AuthenticationSession;
import com.checkon.account.infrastructure.persistence.AuthenticationSessionRepository;
import com.checkon.roster.infrastructure.persistence.TeacherProfileRepository;

/**
 * 서명된 JWT claim을 현재 DB 상태와 대조해 Spring Security 인증 객체를 만든다.
 */
@Service
public class AuthenticatedAccountService {

	private final AuthenticationSessionRepository sessionRepository;
	private final TeacherProfileRepository teacherProfileRepository;
	private final Clock clock;

	public AuthenticatedAccountService(
		AuthenticationSessionRepository sessionRepository,
		TeacherProfileRepository teacherProfileRepository,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.teacherProfileRepository = teacherProfileRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public UsernamePasswordAuthenticationToken authenticate(Jwt jwt) {
		try {
			UUID accountId = UUID.fromString(jwt.getSubject());
			UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
			AccountRole claimedRole = AccountRole.valueOf(
				jwt.getClaimAsString("role")
			);
			AuthenticationSession session = sessionRepository
				.findWithAccountById(sessionId)
				.orElseThrow(InvalidAccessTokenException::new);
			Account account = session.account();
			session.requireUsable(Instant.now(clock));
			if (!account.id().equals(accountId)
				|| account.role() != claimedRole
				|| !account.isActive()) {
				throw new InvalidAccessTokenException();
			}
			UUID teacherProfileId = account.role() == AccountRole.TEACHER
				? teacherProfileRepository.findByAccount_Id(account.id())
					.orElseThrow(InvalidAccessTokenException::new)
					.id()
				: null;
			AuthenticatedAccount principal = new AuthenticatedAccount(
				account.id(),
				account.role(),
				teacherProfileId,
				session.id()
			);
			return UsernamePasswordAuthenticationToken.authenticated(
				principal,
				null,
				java.util.List.of(new SimpleGrantedAuthority(
					"ROLE_" + account.role().name()
				))
			);
		}
		catch (IllegalArgumentException | IllegalStateException exception) {
			throw new InvalidAccessTokenException();
		}
	}

	static final class InvalidAccessTokenException extends AuthenticationException {

		InvalidAccessTokenException() {
			super("invalid access token");
		}
	}
}
