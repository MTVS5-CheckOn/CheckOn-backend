package com.checkon.detection.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "detection_request_attempts")
public class DetectionRequestAttempt {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "detection_run_id", nullable = false)
	private DetectionRun detectionRun;

	@Column(name = "request_id", nullable = false, length = 120)
	private String requestId;

	@Column(name = "attempt_number", nullable = false)
	private int attemptNumber;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DetectionRequestAttemptStatus status;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "http_status")
	private Integer httpStatus;

	@Column(name = "error_code", length = 60)
	private String errorCode;

	protected DetectionRequestAttempt() {
	}

	DetectionRequestAttempt(
		UUID id,
		DetectionRun detectionRun,
		String requestId,
		int attemptNumber,
		Instant requestedAt
	) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.detectionRun = Objects.requireNonNull(
			detectionRun,
			"detectionRun must not be null"
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

	void markSucceeded(Integer httpStatus, Instant completedAt) {
		requireRequested(DetectionRequestAttemptStatus.SUCCEEDED);
		if (httpStatus != null && (httpStatus < 200 || httpStatus >= 300)) {
			throw new IllegalArgumentException(
				"successful attempt HTTP status must be null or 2xx"
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
		return detectionRun.id();
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
