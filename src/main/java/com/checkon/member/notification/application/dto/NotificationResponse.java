package com.checkon.member.notification.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.common.notification.NotificationType;

/**
 * 계약 {@code Notification} (member-api.yaml:2359-2374).
 *
 * <p>🔴 {@code body} 는 nullable — 키 남기고 값 null. {@code target} 은 studentId 나
 * resourceId 중 하나만 있어도 발행 가능하다. 둘 다 null 이면 target 자체가 null 이다.</p>
 */
public record NotificationResponse(
	UUID notificationId,
	NotificationType type,
	String title,
	String body,
	Instant createdAt,
	boolean read,
	Target target
) {

	/** 계약의 {@code target} 오브젝트. 둘 다 없으면 상위에서 target=null 로 뺀다. */
	public record Target(UUID studentId, UUID resourceId) {
	}
}
