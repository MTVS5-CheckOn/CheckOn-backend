package com.checkon.member.integration.account;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.member.common.security.MemberPrincipal;
import com.checkon.member.common.security.MemberPrincipalProvider;
import com.checkon.member.common.security.MemberRole;

/**
 * 기존 인증 주체({@code AuthenticatedAccount})를 member 타입으로 옮긴다.
 *
 * <p>member 에서 남의 패키지를 import 하는 곳은 {@code integration/} 뿐이다(코드 규칙 G2).
 * 강사 토큰은 member 역할이 없으므로 비어 있는 값을 돌려주고, 경로 인가는 보안 체인이 막는다.</p>
 */
@Component
public class SecurityContextMemberPrincipalProvider implements MemberPrincipalProvider {

	@Override
	public Optional<MemberPrincipal> currentPrincipal() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null
			|| !(authentication.getPrincipal() instanceof AuthenticatedAccount account)) {
			return Optional.empty();
		}
		return toMemberRole(account.role())
			.map(role -> new MemberPrincipal(account.accountId(), role, account.sessionId()));
	}

	private Optional<MemberRole> toMemberRole(AccountRole role) {
		if (role == AccountRole.STUDENT) {
			return Optional.of(MemberRole.STUDENT);
		}
		if (role == AccountRole.PARENT) {
			return Optional.of(MemberRole.PARENT);
		}
		return Optional.empty();
	}
}
