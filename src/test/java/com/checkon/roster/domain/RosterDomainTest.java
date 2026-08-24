package com.checkon.roster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.domain.EmailAddress;

/**
 * DB에 도달하기 전에 지켜야 할 Roster 불변식과 단방향 상태 전이를 검증한다.
 */
class RosterDomainTest {

	private static final Instant STARTED_AT =
		Instant.parse("2026-07-31T00:00:00Z");
	private static final UUID TEACHER_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000001");
	private static final UUID STUDENT_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000002");

	@Test
	void acceptsNullableAndHighSchoolGradesOnly() {
		assertThat(StudentProfile.create("학생", null, STARTED_AT).grade()).isNull();
		assertThat(StudentProfile.create("1학년", 1, STARTED_AT).grade()).isEqualTo(1);
		assertThat(StudentProfile.create("2학년", 2, STARTED_AT).grade()).isEqualTo(2);
		assertThat(StudentProfile.create("3학년", 3, STARTED_AT).grade()).isEqualTo(3);

		assertThatThrownBy(() -> StudentProfile.create("학생", 0, STARTED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StudentProfile.create("학생", 4, STARTED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void teacherProfileRequiresTeacherAccount() {
		Account studentAccount = Account.register(
			EmailAddress.of("student@example.com"),
			AccountRole.STUDENT,
			STARTED_AT
		);

		assertThatThrownBy(() -> TeacherProfile.create(
			studentAccount,
			"강사가 아님",
			STARTED_AT
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("TEACHER");
	}

	@Test
	void relationshipRequiresIdentifiersAndTimes() {
		assertThatThrownBy(() -> TeacherStudentRelationship.start(
			null,
			STUDENT_ID,
			STARTED_AT,
			STARTED_AT
		)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> TeacherStudentRelationship.start(
			TEACHER_ID,
			null,
			STARTED_AT,
			STARTED_AT
		)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> TeacherStudentRelationship.start(
			TEACHER_ID,
			STUDENT_ID,
			null,
			STARTED_AT
		)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void relationshipCanEndOnceAndCannotMoveBackToActive() {
		TeacherStudentRelationship relationship =
			TeacherStudentRelationship.start(
				TEACHER_ID,
				STUDENT_ID,
				STARTED_AT,
				STARTED_AT
			);
		Instant endedAt = STARTED_AT.plusSeconds(60);

		relationship.end(endedAt);

		assertThat(relationship.status()).isEqualTo(RelationshipStatus.ENDED);
		assertThat(relationship.endedAt()).isEqualTo(endedAt);
		assertThatThrownBy(() -> relationship.end(endedAt.plusSeconds(1)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void relationshipRejectsEndBeforeStart() {
		TeacherStudentRelationship relationship =
			TeacherStudentRelationship.start(
				TEACHER_ID,
				STUDENT_ID,
				STARTED_AT,
				STARTED_AT
			);

		assertThatThrownBy(() -> relationship.end(STARTED_AT.minusSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}

