package com.checkon.roster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClassGroupTest {
	private static final UUID TEACHER = UUID.randomUUID();
	private static final Instant CREATED_AT = Instant.parse("2026-08-09T00:00:00Z");

	@Test
	void createsAndNormalizesRequiredDisplayFields() {
		ClassGroup classGroup = ClassGroup.create(
			TEACHER, "  수능 국어 대비 반  ", "  국어  ", "앞 공백을 보존하는 메모", CREATED_AT
		);

		assertThat(classGroup.name()).isEqualTo("수능 국어 대비 반");
		assertThat(classGroup.subject()).isEqualTo("국어");
		assertThat(classGroup.memo()).isEqualTo("앞 공백을 보존하는 메모");
		assertThat(classGroup.status()).isEqualTo(ClassGroupStatus.ACTIVE);
		assertThat(classGroup.createdAt()).isEqualTo(CREATED_AT);
		assertThat(classGroup.updatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void acceptsFieldBoundaries() {
		ClassGroup classGroup = ClassGroup.create(
			TEACHER, "가", "나".repeat(100), "다".repeat(1000), CREATED_AT
		);

		assertThat(classGroup.name()).hasSize(1);
		assertThat(classGroup.subject()).hasSize(100);
		assertThat(classGroup.memo()).hasSize(1000);
	}

	@Test
	void rejectsInvalidRequiredFieldsAndOversizedMemo() {
		assertThatThrownBy(() -> ClassGroup.create(
			TEACHER, " ", "국어", null, CREATED_AT
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClassGroup.create(
			TEACHER, "가".repeat(101), "국어", null, CREATED_AT
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClassGroup.create(
			TEACHER, "반", " ", null, CREATED_AT
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClassGroup.create(
			TEACHER, "반", "나".repeat(101), null, CREATED_AT
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClassGroup.create(
			TEACHER, "반", "국어", "다".repeat(1001), CREATED_AT
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void updatesOnlyAnActiveClass() {
		ClassGroup classGroup = ClassGroup.create(
			TEACHER, "기존 반", "국어", null, CREATED_AT
		);
		Instant updatedAt = CREATED_AT.plusSeconds(60);

		classGroup.updateDetails("수정 반", "수학", "메모", updatedAt);

		assertThat(classGroup.name()).isEqualTo("수정 반");
		assertThat(classGroup.subject()).isEqualTo("수학");
		assertThat(classGroup.memo()).isEqualTo("메모");
		assertThat(classGroup.updatedAt()).isEqualTo(updatedAt);
	}

	@Test
	void archiveIsIdempotentAndPreservesTheFirstArchiveTime() {
		ClassGroup classGroup = ClassGroup.create(
			TEACHER, "반", "국어", null, CREATED_AT
		);
		Instant archivedAt = CREATED_AT.plusSeconds(60);

		assertThat(classGroup.archive(archivedAt)).isTrue();
		assertThat(classGroup.archive(archivedAt.plusSeconds(60))).isFalse();
		assertThat(classGroup.status()).isEqualTo(ClassGroupStatus.ARCHIVED);
		assertThat(classGroup.updatedAt()).isEqualTo(archivedAt);
		assertThatThrownBy(() -> classGroup.updateDetails(
			"수정", "수학", null, archivedAt.plusSeconds(120)
		)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsAStateTimeBeforeCreation() {
		ClassGroup classGroup = ClassGroup.create(
			TEACHER, "반", "국어", null, CREATED_AT
		);

		assertThatThrownBy(() -> classGroup.updateDetails(
			"수정", "수학", null, CREATED_AT.minusNanos(1)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> classGroup.archive(CREATED_AT.minusNanos(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectedUpdateDoesNotPartiallyChangeTheAggregate() {
		ClassGroup classGroup = ClassGroup.create(
			TEACHER, "기존 반", "국어", "기존 메모", CREATED_AT
		);

		assertThatThrownBy(() -> classGroup.updateDetails(
			"바뀌면 안 되는 이름",
			"과목".repeat(51),
			"새 메모",
			CREATED_AT.plusSeconds(1)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThat(classGroup.name()).isEqualTo("기존 반");
		assertThat(classGroup.subject()).isEqualTo("국어");
		assertThat(classGroup.memo()).isEqualTo("기존 메모");
		assertThat(classGroup.updatedAt()).isEqualTo(CREATED_AT);
	}
}
