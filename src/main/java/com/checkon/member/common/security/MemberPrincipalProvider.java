package com.checkon.member.common.security;

import java.util.Optional;

/**
 * 현재 요청의 인증 주체를 member 타입으로 돌려주는 포트.
 *
 * <p>구현은 {@code member/integration/account} 에 둔다 — 기존 인증 타입을 아는 곳은 거기뿐이다.</p>
 */
public interface MemberPrincipalProvider {

	/** 인증되지 않았거나 member 역할이 아니면 비어 있다. */
	Optional<MemberPrincipal> currentPrincipal();
}
