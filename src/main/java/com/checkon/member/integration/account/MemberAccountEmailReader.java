package com.checkon.member.integration.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 계정 식별자로 이메일을 읽는다. 학생 로그인이 공개 ID 를 이메일로 옮길 때만 쓴다.
 *
 * <p>{@code accounts} 에는 RLS 가 없다(실측). 엔티티를 새로 매핑하지 않고 읽기만 한다.</p>
 */
@Component
public class MemberAccountEmailReader {

	private static final String FIND_EMAIL = "SELECT email FROM accounts WHERE id = ?";

	private final JdbcTemplate jdbcTemplate;

	public MemberAccountEmailReader(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<String> findEmail(UUID accountId) {
		return jdbcTemplate.query(FIND_EMAIL, rs -> rs.next()
			? Optional.ofNullable(rs.getString(1))
			: Optional.<String>empty(), accountId);
	}
}
