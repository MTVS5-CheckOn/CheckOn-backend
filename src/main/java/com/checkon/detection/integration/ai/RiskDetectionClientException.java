package com.checkon.detection.integration.ai;

public class RiskDetectionClientException extends RuntimeException {

	private final Reason reason;
	private final Integer httpStatus;

	private RiskDetectionClientException(
		Reason reason,
		Integer httpStatus,
		String message,
		Throwable cause
	) {
		super(message, cause);
		this.reason = reason;
		this.httpStatus = httpStatus;
	}

	public static RiskDetectionClientException idempotencyConflict(Throwable cause) {
		return new RiskDetectionClientException(
			Reason.IDEMPOTENCY_CONFLICT,
			409,
			"AI detection request conflicts with an existing idempotency key",
			cause
		);
	}

	public static RiskDetectionClientException httpError(int status, Throwable cause) {
		return new RiskDetectionClientException(
			Reason.HTTP_ERROR,
			status,
			"AI detection request failed with HTTP status " + status,
			cause
		);
	}

	public static RiskDetectionClientException emptyResponse() {
		return new RiskDetectionClientException(
			Reason.EMPTY_RESPONSE,
			null,
			"AI detection response body is empty",
			null
		);
	}

	public Reason reason() {
		return reason;
	}

	public Integer httpStatus() {
		return httpStatus;
	}

	public enum Reason {
		IDEMPOTENCY_CONFLICT,
		HTTP_ERROR,
		EMPTY_RESPONSE
	}
}
