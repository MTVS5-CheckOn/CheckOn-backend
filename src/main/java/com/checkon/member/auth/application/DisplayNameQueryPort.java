package com.checkon.member.auth.application;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code member_display_names} 의 <b>읽기</b> 문. 소유는 {@code auth} 다.
 *
 * <p>설계 §3-1 교차 전수표가 {@code membership}(자녀 이름) · {@code profile} ·
 * {@code consultation} · {@code report} 를 이 포트의 사용자로 지정한다. 소비자가 소유자의
 * Repository 를 직접 import 하지 않는다.</p>
 *
 * <p>🔴 남의 계정 이름을 읽으려면 호출자가 같은 트랜잭션에서 계정 범위를 먼저 열어야 한다
 * ({@code MemberDatabaseContext.withVerifiedAccountScope}). V39 의
 * {@code member_display_names_scope_select} 가 {@code current_checkon_scope_account_id()} 를
 * 요구한다. 열지 않으면 빈 값이다 — 예외가 아니다.</p>
 */
public interface DisplayNameQueryPort {

	Optional<String> findDisplayName(UUID accountId);
}
