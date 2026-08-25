package com.checkon.member.integration.account;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.common.security.MemberRole;

/**
 * 로그인 결과를 member 타입으로만 표현한다.
 *
 * <p>🔴 기존 {@code AuthenticationResult} 를 그대로 돌려주면 호출부({@code auth/application} ·
 * {@code auth/presentation})가 남의 패키지를 import 하게 된다 — 코드 규칙 G2 위반이다.
 * 경계를 넘는 타입은 {@code integration/} 안에서 갈아끼운다.</p>
 *
 * <p>{@code teacherProfileId} 를 두지 않는다. 계약 {@code member-api.yaml:1729} 가
 * <i>"학생·학부모는 항상 null"</i> 이라고 적었으니 그 상수를 응답 조립부에서 채운다 —
 * 이 record 가 항상 null 인 필드를 나르면 다음 사람이 채워도 되는 값으로 오해한다.</p>
 */
public record MemberAuthentication(
	String accessToken,
	Instant accessTokenExpiresAt,
	String refreshToken,
	UUID accountId,
	MemberRole role,
	String email
) {
}
