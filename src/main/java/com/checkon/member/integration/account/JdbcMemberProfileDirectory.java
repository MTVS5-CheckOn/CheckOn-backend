package com.checkon.member.integration.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.checkon.member.common.security.MemberProfileDirectory;

/**
 * {@code student_profiles} · {@code parent_profiles} 를 읽어 프로필 식별자를 찾는다.
 *
 * <p>두 테이블은 승우님 소유라 엔티티를 새로 만들지 않고 읽기 전용 조회만 한다.
 * 계정당 프로필은 하나다({@code uq_student_profiles_account} · {@code uq_parent_profiles_account}).</p>
 */
@Component
public class JdbcMemberProfileDirectory implements MemberProfileDirectory {

	private static final String STUDENT_QUERY =
		"SELECT id FROM student_profiles WHERE account_id = ?";
	private static final String PARENT_QUERY =
		"SELECT id FROM parent_profiles WHERE account_id = ?";

	private final JdbcTemplate jdbcTemplate;

	public JdbcMemberProfileDirectory(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<UUID> findStudentProfileId(UUID accountId) {
		return queryProfileId(STUDENT_QUERY, accountId);
	}

	@Override
	public Optional<UUID> findParentProfileId(UUID accountId) {
		return queryProfileId(PARENT_QUERY, accountId);
	}

	private Optional<UUID> queryProfileId(String sql, UUID accountId) {
		return jdbcTemplate.query(sql, rs -> rs.next()
			? Optional.of(rs.getObject(1, UUID.class))
			: Optional.empty(), accountId);
	}
}
