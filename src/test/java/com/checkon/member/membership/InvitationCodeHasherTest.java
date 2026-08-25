package com.checkon.member.membership;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.checkon.member.membership.application.InvitationCodeHasher;

class InvitationCodeHasherTest {

	/** V38 의 ck_member_invitation_codes_hash 와 같은 형식이다. */
	private static final String FORMAT = "^sha256:[0-9a-f]{64}$";

	@Test
	@DisplayName("해시는 DB CHECK 형식을 만족한다")
	void producesContractedFormat() {
		assertThat(InvitationCodeHasher.hash("INVITE-2026")).matches(FORMAT);
	}

	@Test
	@DisplayName("대소문자·앞뒤 공백이 달라도 같은 해시다 — 아니면 사용자가 틀린 안내를 받는다")
	void normalizesBeforeHashing() {
		String expected = InvitationCodeHasher.hash("INVITE-2026");
		assertThat(InvitationCodeHasher.hash("  invite-2026 ")).isEqualTo(expected);
		assertThat(InvitationCodeHasher.hash("Invite-2026")).isEqualTo(expected);
	}

	@Test
	@DisplayName("다른 코드는 다른 해시다")
	void differentCodesDiffer() {
		assertThat(InvitationCodeHasher.hash("CODE-A"))
			.isNotEqualTo(InvitationCodeHasher.hash("CODE-B"));
	}

	@Test
	@DisplayName("🔴 해시에 평문이 섞이지 않는다")
	void neverLeaksPlaintext() {
		assertThat(InvitationCodeHasher.hash("SECRET42")).doesNotContain("SECRET42");
	}

	@Test
	@DisplayName("값이 없으면 null 이다 — 빈 코드로 조회하지 않는다")
	void returnsNullWhenBlank() {
		assertThat(InvitationCodeHasher.hash(null)).isNull();
		assertThat(InvitationCodeHasher.hash("   ")).isNull();
	}
}
