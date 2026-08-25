package com.checkon.member.common.presentation;

import java.util.UUID;

/**
 * ping 응답 본문. 🔴 Map 으로 내려보내지 않는다(코드 규칙 G12) — 경계 타입은 record 로 고정한다.
 *
 * @param role      호출자의 member 역할
 * @param accountId 호출자의 계정 식별자
 */
public record MemberPingResult(String role, UUID accountId) {
}
