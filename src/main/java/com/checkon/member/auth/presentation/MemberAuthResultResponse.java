package com.checkon.member.auth.presentation;

import java.time.Instant;
import java.util.UUID;

/**
 * 계약 {@code MemberAuthResult}(member-api.yaml:1712-1729).
 *
 * <p>🔴 계약 {@code :1714} — <i>"기존 {@code POST /api/v1/auth/login} 의 {@code data} 와 같은
 * 모양이다. 프론트가 한 어댑터로 처리한다."</i> 그래서 필드 이름·중첩 구조를 그대로 맞춘다.</p>
 *
 * <p>🔴 {@code refreshToken} 은 본문에 <b>넣지 않는다</b>. 계약이 Set-Cookie 로만 내리게 했고,
 * 본문에 실으면 HttpOnly 의 의미가 사라진다.</p>
 */
public record MemberAuthResultResponse(
	String accessToken,
	Instant accessTokenExpiresAt,
	Account account
) {

	/** @param teacherProfileId 🔴 학생·학부모는 항상 {@code null}(계약 {@code :1729}) */
	public record Account(UUID id, String role, String email, UUID teacherProfileId) {
	}
}
