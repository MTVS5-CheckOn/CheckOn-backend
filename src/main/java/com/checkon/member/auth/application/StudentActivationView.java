package com.checkon.member.auth.application;

import java.time.Instant;

import com.checkon.member.auth.domain.MemberActivationStatus;

/**
 * 계약 {@code StudentActivation}(member-api.yaml:1693-1699).
 *
 * <p>🔴 {@code activatedAt} 만 {@code nullable: true} 다. 대기 중에는 키를 두고 값을 null 로
 * 둔다. {@code status} · {@code studentPublicId} 는 nullable 이 아니라 항상 값이 있어야 한다.</p>
 */
public record StudentActivationView(
	MemberActivationStatus status,
	String studentPublicId,
	Instant activatedAt
) {
}
