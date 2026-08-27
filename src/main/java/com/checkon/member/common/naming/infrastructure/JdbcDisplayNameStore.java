package com.checkon.member.common.naming.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.common.naming.DisplayNameStore;

/**
 * {@code member_display_names}(V39) JDBC 어댑터.
 *
 * <p>🔴 RLS 대상이다 — 호출 트랜잭션은 {@link com.checkon.member.common.persistence
 * .MemberDatabaseContext#setCurrentAccount(UUID)} 를 먼저 부른다. 그렇지 않으면
 * SELECT 가 예외가 아니라 <b>0행</b>이다(설계 §6-4-3).</p>
 *
 * <p>학부모가 자녀 이름을 읽을 때는 {@link com.checkon.member.common.persistence
 * .MemberDatabaseContext#withVerifiedAccountScope} 안에서 부른다 —
 * {@code member_display_names_scope_select}(V39) 가 {@code current_checkon_scope_account_id()}
 * 를 요구한다.</p>
 */
@Repository
public class JdbcDisplayNameStore implements DisplayNameStore {

	private static final String SELECT = """
		SELECT display_name FROM member_display_names WHERE account_id = ?
		""";

	// 🔴 UPSERT. 없으면 INSERT, 있으면 UPDATE — self_insert · self_update 정책 두 가지가 모두
	//    같은 술어(account_id = current_checkon_account_id())라 정책 상 통과한다.
	private static final String UPSERT = """
		INSERT INTO member_display_names (account_id, display_name, created_at, updated_at)
		VALUES (?, ?, ?, ?)
		ON CONFLICT (account_id) DO UPDATE
		    SET display_name = EXCLUDED.display_name,
		        updated_at   = EXCLUDED.updated_at
		""";

	private final JdbcTemplate jdbcTemplate;

	public JdbcDisplayNameStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<String> read(UUID accountId) {
		return jdbcTemplate.query(SELECT, rs -> rs.next()
			? Optional.of(rs.getString(1))
			: Optional.<String>empty(), accountId);
	}

	@Override
	public void upsert(UUID accountId, String displayName, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(UPSERT, accountId, displayName, at, at);
	}
}
