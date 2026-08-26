package com.checkon.member.common.security;

import java.util.UUID;

/**
 * 인증 필터가 세운 주체 중 member 가 쓰는 부분만 옮긴 값.
 *
 * @param accountId 계정 식별자
 * @param role      member 역할. 강사 토큰이면 이 값을 만들지 않는다
 * @param sessionId 세션 식별자
 */
public record MemberPrincipal(UUID accountId, MemberRole role, UUID sessionId) {
}
