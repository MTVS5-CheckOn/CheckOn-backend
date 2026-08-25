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
 * <p>🔴 {@code notificationsEnabled} 는 <b>키 자체를 넣지 않는다</b>(MB-32).
 * 계약 {@code member-api.yaml:1733} 의 {@code required: [accountId, role, name]} 에 없고,
 * {@code :1748} 은 {@code notificationsEnabled: { type: boolean }} 으로 <b>nullable 이 아니다</b> —
 * 즉 null 로도 채울 수 없다. 남는 선택지는 값을 지어내거나 키를 빼는 것뿐이고, 알림 설정
 * 테이블은 PR6 소유라 지금은 원본이 없다. 지어내지 않는다(절대 규칙 5).</p>
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
	List<MemberTeacherSummary> teachers
) {
}
