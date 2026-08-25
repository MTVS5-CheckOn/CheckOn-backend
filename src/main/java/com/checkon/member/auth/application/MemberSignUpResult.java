package com.checkon.member.auth.application;

import java.util.UUID;

import com.checkon.member.auth.domain.MemberActivationStatus;

/**
 * 가입 결과.
 *
 * <p>🔴 학부모는 {@code studentPublicId} · {@code activationStatus} 가 {@code null} 이다.
 * 빈 문자열이나 {@code "NONE"} 으로 채우지 않는다 — 모르는 값이 아니라 <b>없는 값</b>이고,
 * 응답에서는 키가 존재하고 값이 null 이어야 프론트 스키마가 깨지지 않는다.</p>
 */
public record MemberSignUpResult(
	UUID accountId,
	String role,
	String studentPublicId,
	MemberActivationStatus activationStatus
) {
}
