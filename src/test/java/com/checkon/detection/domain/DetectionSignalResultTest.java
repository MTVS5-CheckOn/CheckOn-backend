package com.checkon.detection.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DetectionSignalResultTest {

	@Test
	void createsSignalWithAtLeastOneEvidence() {
		DetectionSignalResult result = createResult(
			BigDecimal.ONE,
			List.of(new DetectionResultEvidenceDraft(
				UUID.randomUUID(),
				"learning_event",
				"le_1",
				"정답률 하락 근거"
			))
		);

		assertThat(result.evidence()).hasSize(1);
		assertThat(result.evidence().getFirst().detectionSignalResultId())
			.isEqualTo(result.id());
	}

	@Test
	void rejectsSignalWithoutEvidence() {
		assertThatThrownBy(() -> createResult(BigDecimal.ONE, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("signal result must have at least one evidence");
	}

	@Test
	void acceptsZeroScoreBecauseAiCanRaiseSignalWithZeroScore() {
		DetectionSignalResult result = createResult(
			BigDecimal.ZERO,
			List.of(new DetectionResultEvidenceDraft(
				UUID.randomUUID(),
				"learning_event",
				"le_1",
				"제출 저조 근거"
			))
		);

		assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void rejectsScoreOutsideContractRange() {
		assertThatThrownBy(() -> createResult(
			new BigDecimal("1.01"),
			List.of(new DetectionResultEvidenceDraft(
				UUID.randomUUID(),
				"learning_event",
				"le_1",
				"근거"
			))
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("score must be between 0 and 1");
	}

	private DetectionSignalResult createResult(
		BigDecimal score,
		List<DetectionResultEvidenceDraft> evidence
	) {
		return DetectionSignalResult.create(
			UUID.randomUUID(),
			UUID.randomUUID(),
			"signal-1",
			"st_02",
			"cl_a1",
			"R1",
			"acc_drop",
			"정답률 하락",
			score,
			1,
			false,
			DetectionLifecycle.NEW,
			"정답률이 평소보다 떨어졌어요.",
			true,
			false,
			evidence,
			Instant.parse("2026-07-28T02:10:03Z")
		);
	}
}
