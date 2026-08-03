package com.checkon.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LearningRecordTest {
	private static final UUID TEACHER = UUID.randomUUID();
	private static final UUID STUDENT = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Test
	void validatesRequiredFieldsAndNormalizesBlankExternalReferenceToNull() {
		LearningRecord record = LearningRecord.create(draft("  "), NOW);
		assertThat(record.externalRecordRef()).isNull();
		assertThatThrownBy(() -> LearningRecord.create(new LearningRecord.Draft(
			null, STUDENT, null, LearningRecordType.SOLVE, NOW, "backend", null,
			true, 1, 1, null, null, null, null, null), NOW))
			.isInstanceOf(NullPointerException.class).hasMessageContaining("teacherId");
		assertThatThrownBy(() -> LearningRecord.create(new LearningRecord.Draft(
			TEACHER, STUDENT, null, LearningRecordType.SOLVE, null, "backend", null,
			true, 1, 1, null, null, null, null, null), NOW))
			.isInstanceOf(NullPointerException.class).hasMessageContaining("occurredAt");
	}

	@Test
	void exposesNoMutationAndKeepsOwnershipAndOccurrenceAsCreated() {
		LearningRecord record = LearningRecord.create(draft(null), NOW);
		assertThat(record.teacherId()).isEqualTo(TEACHER);
		assertThat(record.studentId()).isEqualTo(STUDENT);
		assertThat(record.occurredAt()).isEqualTo(NOW);
		assertThat(record.correct()).isTrue();
		assertThat(record.updatedAt()).isEqualTo(NOW);
	}

	private LearningRecord.Draft draft(String externalRef) {
		return new LearningRecord.Draft(TEACHER, STUDENT, null,
			LearningRecordType.SOLVE, NOW, "backend", externalRef, true,
			10, 100, "reading", "common", "infer", "mcq", null);
	}
}
