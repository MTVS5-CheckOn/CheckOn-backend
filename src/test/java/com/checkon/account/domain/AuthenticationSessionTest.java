package com.checkon.account.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh Token 회전과 폐기가 되돌릴 수 없는 세션 상태 전이임을 보장한다.
 */
class AuthenticationSessionTest {

	private static final Instant ISSUED_AT = Instant.parse("2026-07-31T00:00:00Z");

	@Test
	void rotatesOnlyAnActiveUnexpiredSession() {
		AuthenticationSession session = session();

		session.rotate(hash('b'), ISSUED_AT.plusSeconds(60));

		assertThat(session.refreshTokenHash()).isEqualTo(hash('b'));
		assertThat(session.rotationCount()).isEqualTo(1);
		assertThatThrownBy(() -> session.rotate(
			hash('c'),
			ISSUED_AT.plusSeconds(3600)
		)).isInstanceOf(IllegalStateException.class);
		assertThat(session.status()).isEqualTo(AuthenticationSessionStatus.EXPIRED);
	}

	@Test
	void revokedSessionCannotBeRotated() {
		AuthenticationSession session = session();
		session.revoke(ISSUED_AT.plusSeconds(10));

		assertThat(session.status()).isEqualTo(AuthenticationSessionStatus.REVOKED);
		assertThatThrownBy(() -> session.rotate(
			hash('b'),
			ISSUED_AT.plusSeconds(20)
		)).isInstanceOf(IllegalStateException.class);
	}

	private static AuthenticationSession session() {
		Account account = Account.register(
			EmailAddress.of("teacher@example.com"),
			AccountRole.TEACHER,
			ISSUED_AT
		);
		return AuthenticationSession.issue(
			account,
			hash('a'),
			ISSUED_AT,
			ISSUED_AT.plusSeconds(3600)
		);
	}

	private static String hash(char value) {
		return "sha256:" + String.valueOf(value).repeat(64);
	}
}
