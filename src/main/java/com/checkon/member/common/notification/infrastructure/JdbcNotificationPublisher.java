package com.checkon.member.common.notification.infrastructure;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.checkon.member.common.notification.NotificationPort;
import com.checkon.member.common.notification.NotificationRequest;

/**
 * {@link NotificationPort} 의 JDBC 어댑터. V41 의 {@code member_notifications} 에 INSERT 한다.
 *
 * <p>🔴 {@code ON CONFLICT ON CONSTRAINT uq_member_notifications_source DO NOTHING} —
 * 같은 발행 원본으로 두 번 오면 조용히 무시. 예외를 던지지 않는다.</p>
 *
 * <p>🔴 INSERT 정책은 {@code current_checkon_account_id() IS NOT NULL} 뿐이다(V41 §5-4).
 * 호출 트랜잭션이 발행자 계정 컨텍스트를 미리 설정해야 한다.</p>
 */
@Component
public class JdbcNotificationPublisher implements NotificationPort {

	private static final String INSERT = """
		INSERT INTO member_notifications (
		    recipient_account_id, type, title, body,
		    target_student_id, target_resource_id,
		    source_type, source_id, created_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT ON CONSTRAINT uq_member_notifications_source DO NOTHING
		""";

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public JdbcNotificationPublisher(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	@Override
	public void publish(NotificationRequest request) {
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		jdbcTemplate.update(INSERT,
			request.recipientAccountId(),
			request.type().name(),
			request.title(),
			request.body(),
			request.targetStudentId(),
			request.targetResourceId(),
			request.sourceType(),
			request.sourceId(),
			now
		);
	}
}
