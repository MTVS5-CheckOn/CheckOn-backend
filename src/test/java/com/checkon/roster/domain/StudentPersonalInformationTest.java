package com.checkon.roster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.checkon.account.domain.AccountRole;

class StudentPersonalInformationTest {
	private static final UUID STUDENT = UUID.randomUUID();
	private static final UUID ACCOUNT = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Test
	void createsAndChangesAValidRealName() {
		var information = StudentPersonalInformation.create(
			STUDENT, "김서연", ACCOUNT, AccountRole.TEACHER, NOW
		);
		assertThat(information.realName()).isEqualTo("김서연");

		Instant later = NOW.plusSeconds(1);
		information.changeRealName("김서윤", ACCOUNT, AccountRole.TEACHER, later);
		assertThat(information.realName()).isEqualTo("김서윤");
		assertThat(information.updatedAt()).isEqualTo(later);
	}

	@Test
	void rejectsBlankSurroundingWhitespaceAndOverlongNames() {
		for (String invalid : new String[] { null, "", "   ", " 김서연", "김서연 ", "가".repeat(101) }) {
			assertThatThrownBy(() -> StudentPersonalInformation.create(
				STUDENT, invalid, ACCOUNT, AccountRole.TEACHER, NOW
			)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsANonTeacherUpdater() {
		assertThatThrownBy(() -> StudentPersonalInformation.create(
			STUDENT, "김서연", ACCOUNT, AccountRole.PARENT, NOW
		)).isInstanceOf(IllegalArgumentException.class);
	}
}

