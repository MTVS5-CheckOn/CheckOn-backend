package com.checkon.member.auth.application;

import java.util.List;
import java.util.UUID;

import com.checkon.member.auth.domain.MemberActivationStatus;

/**
 * 앱 bootstrap 응답.
 *
 * <p>🔴 반대 역할의 필드는 <b>키가 존재하고 값이 null</b> 이어야 한다. 키를 빼면 프론트
 * 스키마 검증이 깨진다. record 직렬화는 null 필드도 키를 남긴다.</p>
 *
 * <p>🔴 {@code notificationsEnabled} 는 <b>PR6 이 채운다.</b> V41 이
 * {@code member_notification_preferences} 를 만들었고 부재 시 Settings
 * ({@code checkon.member.notification.default-enabled}) 로 대체한다 — MB-32 CLOSED.</p>
 *
 * <p>{@code studentPublicId} · {@code activationStatus} 는 다르다 — 그 둘은 {@code nullable: true}
 * 라 <b>키가 존재하고 값이 null</b> 이어야 한다.</p>
 */
public record MemberSessionView(
	UUID accountId,
	String role,
	String name,
	UUID studentProfileId,
	UUID parentProfileId,
	MemberActivationStatus activationStatus,
	String studentPublicId,
	List<MemberTeacherSummary> teachers,
	boolean notificationsEnabled
) {
}
