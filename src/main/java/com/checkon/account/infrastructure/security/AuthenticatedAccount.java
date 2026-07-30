package com.checkon.account.infrastructure.security;

import java.util.UUID;

import com.checkon.account.domain.AccountRole;

/**
 * Controller와 application 계층이 신뢰할 수 있는 현재 인증 주체다.
 *
 * <p>요청 헤더의 강사 ID가 아니라 검증된 Account와 Roster 프로필로 만든다.</p>
 */
public record AuthenticatedAccount(
	UUID accountId,
	AccountRole role,
	UUID teacherProfileId,
	UUID sessionId
) {
}
