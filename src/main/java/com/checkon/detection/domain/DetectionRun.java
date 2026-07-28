package com.checkon.detection.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class DetectionRun {

	private static final Pattern SNAPSHOT_HASH_PATTERN =
		Pattern.compile("sha256:[0-9a-f]{64}");

	private final UUID id;
	private final UUID teacherId;
	private final LocalDate analysisDate;
	private final LocalDate weekStart;
	private final String idempotencyKey;
	private final String snapshotHash;
	private final String snapshotPayload;
	private final Instant preparedAt;
	private final List<DetectionRequestAttempt> attempts = new ArrayList<>();

	private DetectionRunStatus status;
	private Instant requestedAt;
	private Instant completedAt;
	private String aiExecutionId;
	private String errorCode;

	private DetectionRun(
		UUID id,
		UUID teacherId,
		LocalDate analysisDate,
		LocalDate weekStart,
		String idempotencyKey,
		String snapshotHash,
		String snapshotPayload,
		Instant preparedAt
	) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.teacherId = Objects.requireNonNull(teacherId, "teacherId must not be null");
		this.analysisDate = Objects.requireNonNull(
			analysisDate,
			"analysisDate must not be null"
		);
		this.weekStart = Objects.requireNonNull(weekStart, "weekStart must not be null");
		this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
		this.snapshotHash = requireSnapshotHash(snapshotHash);
		this.snapshotPayload = requireText(snapshotPayload, "snapshotPayload");
		this.preparedAt = Objects.requireNonNull(preparedAt, "preparedAt must not be null");
		this.status = DetectionRunStatus.PREPARED;
	}

	public static DetectionRun prepare(
		UUID id,
		UUID teacherId,
		LocalDate analysisDate,
		LocalDate weekStart,
		String idempotencyKey,
		String snapshotHash,
		String snapshotPayload,
		Instant preparedAt
	) {
		return new DetectionRun(
			id,
			teacherId,
			analysisDate,
			weekStart,
			idempotencyKey,
			snapshotHash,
			snapshotPayload,
			preparedAt
		);
	}

	public DetectionRequestAttempt startAttempt(
		UUID attemptId,
		String requestId,
		Instant requestedAt
	) {
		if (status != DetectionRunStatus.PREPARED && status != DetectionRunStatus.FAILED) {
			throw invalidTransition(DetectionRunStatus.REQUESTED);
		}
		DetectionRequestAttempt attempt = new DetectionRequestAttempt(
			attemptId,
			id,
			requestId,
			attempts.size() + 1,
			requestedAt
		);

		this.status = DetectionRunStatus.REQUESTED;
		this.requestedAt = attempt.requestedAt();
		this.completedAt = null;
		this.aiExecutionId = null;
		this.errorCode = null;
		this.attempts.add(attempt);
		return attempt;
	}

	public void markSucceeded(
		UUID attemptId,
		String aiExecutionId,
		int httpStatus,
		Instant completedAt
	) {
		requireStatus(DetectionRunStatus.REQUESTED, DetectionRunStatus.SUCCEEDED);
		DetectionRequestAttempt attempt = requireCurrentAttempt(attemptId);
		String executionId = requireText(aiExecutionId, "aiExecutionId");
		Instant completionTime = Objects.requireNonNull(
			completedAt,
			"completedAt must not be null"
		);
		attempt.markSucceeded(httpStatus, completionTime);

		this.status = DetectionRunStatus.SUCCEEDED;
		this.aiExecutionId = executionId;
		this.completedAt = completionTime;
		this.errorCode = null;
	}

	public void markFailed(
		UUID attemptId,
		Integer httpStatus,
		String errorCode,
		Instant completedAt
	) {
		requireStatus(DetectionRunStatus.REQUESTED, DetectionRunStatus.FAILED);
		DetectionRequestAttempt attempt = requireCurrentAttempt(attemptId);
		String failureCode = requireText(errorCode, "errorCode");
		Instant completionTime = Objects.requireNonNull(
			completedAt,
			"completedAt must not be null"
		);
		attempt.markFailed(httpStatus, failureCode, completionTime);

		this.status = DetectionRunStatus.FAILED;
		this.errorCode = failureCode;
		this.completedAt = completionTime;
		this.aiExecutionId = null;
	}

	private DetectionRequestAttempt requireCurrentAttempt(UUID attemptId) {
		Objects.requireNonNull(attemptId, "attemptId must not be null");
		DetectionRequestAttempt current = attempts.getLast();
		if (!current.id().equals(attemptId)) {
			throw new IllegalArgumentException(
				"attemptId must identify the current request attempt"
			);
		}
		return current;
	}

	private void requireStatus(
		DetectionRunStatus required,
		DetectionRunStatus target
	) {
		if (status != required) {
			throw invalidTransition(target);
		}
	}

	private IllegalStateException invalidTransition(DetectionRunStatus target) {
		return new IllegalStateException(
			"Cannot transition detection run from " + status + " to " + target
		);
	}

	private static String requireSnapshotHash(String value) {
		String hash = requireText(value, "snapshotHash");
		if (!SNAPSHOT_HASH_PATTERN.matcher(hash).matches()) {
			throw new IllegalArgumentException(
				"snapshotHash must use sha256:{64 lowercase hex} format"
			);
		}
		return hash;
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

	public UUID id() {
		return id;
	}

	public UUID teacherId() {
		return teacherId;
	}

	public LocalDate analysisDate() {
		return analysisDate;
	}

	public LocalDate weekStart() {
		return weekStart;
	}

	public String idempotencyKey() {
		return idempotencyKey;
	}

	public String snapshotHash() {
		return snapshotHash;
	}

	public String snapshotPayload() {
		return snapshotPayload;
	}

	public Instant preparedAt() {
		return preparedAt;
	}

	public DetectionRunStatus status() {
		return status;
	}

	public Instant requestedAt() {
		return requestedAt;
	}

	public Instant completedAt() {
		return completedAt;
	}

	public String aiExecutionId() {
		return aiExecutionId;
	}

	public String errorCode() {
		return errorCode;
	}

	public List<DetectionRequestAttempt> attempts() {
		return Collections.unmodifiableList(attempts);
	}
}
