package com.checkon.account.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.checkon.account.domain.AuthenticationSession;

import jakarta.persistence.LockModeType;

/**
 * 세션 조회와 Refresh/로그아웃 상태 전이에 필요한 행 잠금을 제공한다.
 */
public interface AuthenticationSessionRepository
	extends JpaRepository<AuthenticationSession, UUID> {

	/**
	 * Refresh 검증과 회전을 직렬화해 동일 토큰의 동시 사용을 하나만 허용한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select session
		from AuthenticationSession session
		join fetch session.account
		where session.refreshTokenHash = :refreshTokenHash
		""")
	Optional<AuthenticationSession> findByRefreshTokenHashForUpdate(
		@Param("refreshTokenHash") String refreshTokenHash
	);

	@Query("""
		select session
		from AuthenticationSession session
		join fetch session.account
		where session.id = :sessionId
		""")
	Optional<AuthenticationSession> findWithAccountById(
		@Param("sessionId") UUID sessionId
	);

	/**
	 * 로그아웃이 Refresh 회전과 경쟁하지 않도록 현재 세션을 잠가 조회한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select session
		from AuthenticationSession session
		join fetch session.account
		where session.id = :sessionId
		""")
	Optional<AuthenticationSession> findByIdForUpdate(
		@Param("sessionId") UUID sessionId
	);
}
