package com.checkon.detection.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class DetectionRequestAttempt {

	private final UUID id;
	private final UUID detectionRunId;
	private final String requestId;
	private final int attemptNumber;
	private final Instant requestedAt;

	private DetectionRequestAttemptStatus status;
	private Instant completedAt;
	private Integer httpStatus;
	private String errorCode;

	DetectionRequestAttempt(
		UUID id,
		UUID detectionRunId,
		String requestId,
		int attemptNumber,
		Instant requestedAt
	) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.detectionRunId = Objects.requireNonNull(
			detectionRunId,
			"detectionRunId must not be null"
		);
		this.requestId = requireText(requestId, "requestId");
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("attemptNumber must be at least 1");
		}
		this.attemptNumber = attemptNumber;
		this.requestedAt = Objects.requireNonNull(
			requestedAt,
			"requestedAt must not be null"
		);
		this.status = DetectionRequestAttemptStatus.REQUESTED;
	}

	void markSucceeded(int httpStatus, Instant completedAt) {
		requireRequested(DetectionRequestAttemptStatus.SUCCEEDED);
		if (httpStatus < 200 || httpStatus >= 300) {
			throw new IllegalArgumentException(
				"successful attempt must have a 2xx HTTP status"
			);
		}
		Instant completionTime = Objects.requireNonNull(
			completedAt,
			"completedAt must not be null"
		);

		this.status = DetectionRequestAttemptStatus.SUCCEEDED;
		this.httpStatus = httpStatus;
		this.completedAt = completionTime;
		this.errorCode = null;
	}

	void markFailed(Integer httpStatus, String errorCode, Instant completedAt) {
		requireRequested(DetectionRequestAttemptStatus.FAILED);
		if (httpStatus != null && (httpStatus < 400 || httpStatus > 599)) {
			throw new IllegalArgumentException(
				"failed attempt HTTP status must be null or between 400 and 599"
			);
		}
		String failureCode = requireText(errorCode, "errorCode");
		Instant completionTime = Objects.requireNonNull(
			completedAt,
			"completedAt must not be null"
		);

		this.status = DetectionRequestAttemptStatus.FAILED;
		this.httpStatus = httpStatus;
		this.errorCode = failureCode;
		this.completedAt = completionTime;
	}

	private void requireRequested(DetectionRequestAttemptStatus target) {
		if (status != DetectionRequestAttemptStatus.REQUESTED) {
			throw new IllegalStateException(
				"Cannot transition detection request attempt from "
					+ status + " to " + target
			);
		}
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

	public UUID detectionRunId() {
		return detectionRunId;
	}

	public String requestId() {
		return requestId;
	}

	public int attemptNumber() {
		return attemptNumber;
	}

	public Instant requestedAt() {
		return requestedAt;
	}

	public DetectionRequestAttemptStatus status() {
		return status;
	}

	public Instant completedAt() {
		return completedAt;
	}

	public Integer httpStatus() {
		return httpStatus;
	}

	public String errorCode() {
		return errorCode;
	}
}
