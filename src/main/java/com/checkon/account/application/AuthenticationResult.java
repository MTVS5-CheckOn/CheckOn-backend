package com.checkon.account.application;

import java.time.Instant;
import java.util.UUID;

import com.checkon.account.domain.AccountRole;

/**
 * application 계층의 인증 결과다. Refresh 원문은 Controller가 쿠키로 한 번만 전달한다.
 */
public record AuthenticationResult(
	String accessToken,
	Instant accessTokenExpiresAt,
	String refreshToken,
	UUID accountId,
	AccountRole role,
	String email,
	UUID teacherProfileId
) {
}
