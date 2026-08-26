package com.checkon.member.auth.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code member_display_names} 접근 (V39).
 *
 * <p>학생·학부모 표시 이름의 유일한 원본이다. {@code student_profiles.alias} 를 덮어쓰지 않는다 —
 * 가입 시 alias 에 같은 값을 초기값으로만 넣고, 이후 member API 는 이 테이블만 읽고 쓴다.</p>
 */
@Repository
public class MemberDisplayNameRepository {

	private static final String INSERT = """
		INSERT INTO member_display_names (account_id, display_name, created_at, updated_at)
		VALUES (?, ?, ?, ?)
		""";

	private static final String FIND = """
		SELECT display_name FROM member_display_names WHERE account_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberDisplayNameRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(UUID accountId, String displayName, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(INSERT, accountId, displayName, at, at);
	}

	public Optional<String> find(UUID accountId) {
		return jdbcTemplate.query(FIND, rs -> rs.next()
			? Optional.of(rs.getString(1))
			: Optional.<String>empty(), accountId);
	}
}
