package com.checkon.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.AuthenticationSession;
import com.checkon.account.infrastructure.persistence.AuthenticationSessionRepository;

/**
 * Access Token이 가리키는 현재 세션 하나만 폐기하는 로그아웃 유스케이스다.
 */
@Service
public class LogoutService {

	private final AuthenticationSessionRepository sessionRepository;
	private final Clock clock;

	public LogoutService(
		AuthenticationSessionRepository sessionRepository,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.clock = clock;
	}

	@Transactional
	public void logout(UUID accountId, UUID sessionId) {
		// sid만으로 세션을 폐기하지 않고 JWT의 sub와 세션 소유 Account를 대조한다.
		// 행 잠금은 로그아웃과 Refresh 회전이 동시에 성공하는 것을 방지한다.
		AuthenticationSession session = sessionRepository
			.findByIdForUpdate(sessionId)
			.filter(candidate -> candidate.accountId().equals(accountId))
			.orElseThrow(InvalidSessionException::new);
		session.revoke(Instant.now(clock));
	}
}
