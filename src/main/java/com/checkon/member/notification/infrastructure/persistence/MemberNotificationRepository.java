package com.checkon.member.notification.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.common.notification.NotificationType;
import com.checkon.member.notification.application.dto.NotificationResponse;

/**
 * {@code member_notifications}(V41) 접근.
 *
 * <p>🔴 <b>계정 컨텍스트 필수</b> — recipient self policy 로 격리한다.
 * 남의 알림은 자동으로 0행이다.</p>
 */
@Repository
public class MemberNotificationRepository {

	private static final String COLUMNS =
		"id, type, title, body, target_student_id, target_resource_id, read_at, created_at";

	// 🔴 created_at DESC, id DESC. 열림 구간 cursor 조건.
	private static final String FIND_PAGE = """
		SELECT %s FROM member_notifications
		WHERE recipient_account_id = ?
		  AND (?::timestamptz IS NULL
		       OR (created_at, id) < (?::timestamptz, ?::uuid))
		ORDER BY created_at DESC, id DESC
		LIMIT ?
		""".formatted(COLUMNS);

	// 🔴 이미 읽은 알림의 read_at 을 덮어쓰지 않는다 — WHERE read_at IS NULL.
	//    0행 UPDATE 도 정상(멱등).
	private static final String MARK_ONE_READ = """
		UPDATE member_notifications
		SET read_at = ?
		WHERE id = ? AND recipient_account_id = ? AND read_at IS NULL
		""";

	// 🔴 부재 판정용 — 남의 알림도 recipient self 정책으로 0행이 나오므로 이 SELECT 하나로 갈린다.
	private static final String FIND_ID = """
		SELECT 1 FROM member_notifications
		WHERE id = ? AND recipient_account_id = ?
		""";

	// 🔴 스냅숏 시각 이하만 갱신. 페이지 루프 금지 — 단일 UPDATE.
	private static final String MARK_ALL_READ = """
		UPDATE member_notifications
		SET read_at = ?
		WHERE recipient_account_id = ? AND read_at IS NULL AND created_at <= ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberNotificationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<NotificationResponse> findPage(
		UUID recipientAccountId,
		OffsetDateTime cursorCreatedAt,
		UUID cursorId,
		int limit
	) {
		return jdbcTemplate.query(FIND_PAGE, (rs, rowNum) -> map(rs),
			recipientAccountId, cursorCreatedAt, cursorCreatedAt, cursorId, limit);
	}

	public boolean existsForRecipient(UUID notificationId, UUID recipientAccountId) {
		return jdbcTemplate.query(FIND_ID,
			(java.sql.ResultSet rs) -> rs.next(),
			notificationId, recipientAccountId);
	}

	public int markRead(UUID notificationId, UUID recipientAccountId, Instant now) {
		return jdbcTemplate.update(MARK_ONE_READ,
			offset(now), notificationId, recipientAccountId);
	}

	public int markAllRead(UUID recipientAccountId, Instant now) {
		OffsetDateTime at = offset(now);
		return jdbcTemplate.update(MARK_ALL_READ, at, recipientAccountId, at);
	}

	private static NotificationResponse map(ResultSet rs) throws java.sql.SQLException {
		UUID targetStudent = (UUID) rs.getObject(5);
		UUID targetResource = (UUID) rs.getObject(6);
		NotificationResponse.Target target = (targetStudent == null && targetResource == null)
			? null
			: new NotificationResponse.Target(targetStudent, targetResource);
		OffsetDateTime readAt = rs.getObject(7, OffsetDateTime.class);
		return new NotificationResponse(
			rs.getObject(1, UUID.class),
			NotificationType.valueOf(rs.getString(2)),
			rs.getString(3),
			rs.getString(4),
			rs.getObject(8, OffsetDateTime.class).toInstant(),
			readAt != null,
			target
		);
	}

	private static OffsetDateTime offset(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}
}
