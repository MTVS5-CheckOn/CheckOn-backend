package com.checkon.member.common.security;

/**
 * member 경계가 다루는 역할. 강사는 member API 를 쓰지 않으므로 여기 없다.
 *
 * <p>기존 {@code com.checkon.account.domain.AccountRole} 을 그대로 쓰지 않는 이유는
 * 코드 규칙 G2 다 — {@code integration/} 밖에서는 남의 패키지를 import 하지 않는다.
 * 변환은 {@code member/integration/account} 어댑터 한 곳에서만 한다.</p>
 */
public enum MemberRole {
	STUDENT,
	PARENT
}
