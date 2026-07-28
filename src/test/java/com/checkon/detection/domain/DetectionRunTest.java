package com.checkon.detection.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DetectionRunTest {

	private static final String SNAPSHOT_HASH =
		"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	private static final String SNAPSHOT_PAYLOAD =
		"{\"snapshot_meta\":{\"week_start\":\"2026-07-20\"}}";

	@Test
	void preparesRunWithImmutableRetryData() {
		DetectionRun run = prepareRun();

		assertThat(run.status()).isEqualTo(DetectionRunStatus.PREPARED);
		assertThat(run.idempotencyKey()).isEqualTo("tn_demo_teacher:2026-07-28");
		assertThat(run.snapshotHash()).isEqualTo(SNAPSHOT_HASH);
		assertThat(run.snapshotPayload()).isEqualTo(SNAPSHOT_PAYLOAD);
		assertThat(run.requestedAt()).isNull();
	}

	@Test
	void completesRequestedRun() {
		DetectionRun run = prepareRun();
		Instant requestedAt = Instant.parse("2026-07-27T17:10:00Z");
		Instant completedAt = Instant.parse("2026-07-27T17:10:03Z");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000010");

		DetectionRequestAttempt attempt = run.startAttempt(
			attemptId,
			"request-1",
			requestedAt
		);
		run.markSucceeded(attemptId, "execution-1", 200, completedAt);

		assertThat(run.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(run.requestedAt()).isEqualTo(requestedAt);
		assertThat(run.completedAt()).isEqualTo(completedAt);
		assertThat(run.aiExecutionId()).isEqualTo("execution-1");
		assertThat(run.errorCode()).isNull();
		assertThat(attempt.status())
			.isEqualTo(DetectionRequestAttemptStatus.SUCCEEDED);
		assertThat(attempt.httpStatus()).isEqualTo(200);
	}

	@Test
	void rejectsCompletionBeforeRequest() {
		DetectionRun run = prepareRun();

		assertThatThrownBy(() -> run.markSucceeded(
			UUID.randomUUID(),
			"execution-1",
			200,
			Instant.parse("2026-07-27T17:10:03Z")
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Cannot transition detection run from PREPARED to SUCCEEDED");
	}

	@Test
	void retriesFailedRunWithoutChangingSnapshot() {
		DetectionRun run = prepareRun();
		UUID firstAttemptId =
			UUID.fromString("019846dc-7c00-7000-8000-000000000010");
		UUID retryAttemptId =
			UUID.fromString("019846dc-7c00-7000-8000-000000000011");

		run.startAttempt(
			firstAttemptId,
			"request-1",
			Instant.parse("2026-07-27T17:10:00Z")
		);
		run.markFailed(
			firstAttemptId,
			null,
			"TIMEOUT",
			Instant.parse("2026-07-27T17:10:30Z")
		);

		run.startAttempt(
			retryAttemptId,
			"request-2",
			Instant.parse("2026-07-27T17:11:00Z")
		);

		assertThat(run.status()).isEqualTo(DetectionRunStatus.REQUESTED);
		assertThat(run.snapshotHash()).isEqualTo(SNAPSHOT_HASH);
		assertThat(run.snapshotPayload()).isEqualTo(SNAPSHOT_PAYLOAD);
		assertThat(run.idempotencyKey()).isEqualTo("tn_demo_teacher:2026-07-28");
		assertThat(run.errorCode()).isNull();
		assertThat(run.completedAt()).isNull();
		assertThat(run.attempts()).hasSize(2);
		assertThat(run.attempts())
			.extracting(DetectionRequestAttempt::attemptNumber)
			.containsExactly(1, 2);
		assertThat(run.attempts().getFirst().status())
			.isEqualTo(DetectionRequestAttemptStatus.FAILED);
		assertThat(run.attempts().getFirst().errorCode()).isEqualTo("TIMEOUT");
		assertThat(run.attempts().getFirst().httpStatus()).isNull();
		assertThat(run.attempts().getLast().status())
			.isEqualTo(DetectionRequestAttemptStatus.REQUESTED);
	}

	@Test
	void rejectsInvalidSnapshotHashFormat() {
		assertThatThrownBy(() -> DetectionRun.prepare(
			UUID.randomUUID(),
			UUID.randomUUID(),
			LocalDate.of(2026, 7, 28),
			LocalDate.of(2026, 7, 20),
			"tn_demo_teacher:2026-07-28",
			"sha256:test",
			SNAPSHOT_PAYLOAD,
			Instant.parse("2026-07-27T17:09:59Z")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("snapshotHash must use sha256:{64 lowercase hex} format");
	}

	private DetectionRun prepareRun() {
		return DetectionRun.prepare(
			UUID.fromString("019846dc-7c00-7000-8000-000000000001"),
			UUID.fromString("019846dc-7c00-7000-8000-000000000002"),
			LocalDate.of(2026, 7, 28),
			LocalDate.of(2026, 7, 20),
			"tn_demo_teacher:2026-07-28",
			SNAPSHOT_HASH,
			SNAPSHOT_PAYLOAD,
			Instant.parse("2026-07-27T17:09:59Z")
		);
	}
}
