package com.checkon.member.profile.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code member_notification_preferences}(V41) 접근.
 *
 * <p>🔴 계정 컨텍스트 필수 — self select/insert/update 정책이 격리한다.</p>
 *
 * <p>🔴 <b>행 부재를 {@code false} 로 읽지 않는다.</b> 부재 = 기본값이고 기본값은
 * {@link com.checkon.member.profile.application.MemberProfileProperties} 에 있다.</p>
 */
@Repository
public class NotificationPreferenceRepository {

	private static final String FIND = """
		SELECT notifications_enabled FROM member_notification_preferences
		WHERE account_id = ?
		""";

	private static final String UPSERT = """
		INSERT INTO member_notification_preferences
		    (account_id, notifications_enabled, created_at, updated_at)
		VALUES (?, ?, ?, ?)
		ON CONFLICT (account_id) DO UPDATE
		    SET notifications_enabled = EXCLUDED.notifications_enabled,
		        updated_at            = EXCLUDED.updated_at
		""";

	private final JdbcTemplate jdbcTemplate;

	public NotificationPreferenceRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<Boolean> find(UUID accountId) {
		return jdbcTemplate.query(FIND, rs -> rs.next()
			? Optional.of(rs.getBoolean(1))
			: Optional.<Boolean>empty(), accountId);
	}

	public void upsert(UUID accountId, boolean enabled, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(UPSERT, accountId, enabled, at, at);
	}
}
