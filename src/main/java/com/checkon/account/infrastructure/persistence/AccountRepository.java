package com.checkon.account.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.account.domain.Account;

/**
 * Account 영속화와 정규화된 이메일 중복 사전 확인을 제공한다.
 *
 * <p>이 조회는 빠른 오류 응답을 위한 것이며, 동시 가입의 최종 중복 방지는
 * PostgreSQL의 {@code lower(email)} 유일 인덱스가 담당한다.</p>
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

	boolean existsByEmail(String email);

	Optional<Account> findByEmail(String email);
}
