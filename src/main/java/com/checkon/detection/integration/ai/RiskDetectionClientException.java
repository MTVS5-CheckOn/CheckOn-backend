package com.checkon.detection.integration.ai;

public class RiskDetectionClientException extends RuntimeException {

	private final Reason reason;
	private final Integer httpStatus;
	private final String responseBody;

	private RiskDetectionClientException(
		Reason reason,
		Integer httpStatus,
		String responseBody,
		String message,
		Throwable cause
	) {
		super(message, cause);
		this.reason = reason;
		this.httpStatus = httpStatus;
		this.responseBody = responseBody;
	}

	public static RiskDetectionClientException idempotencyConflict(Throwable cause) {
		return idempotencyConflict(null, cause);
	}

	public static RiskDetectionClientException idempotencyConflict(
		String responseBody,
		Throwable cause
	) {
		return new RiskDetectionClientException(
			Reason.IDEMPOTENCY_CONFLICT,
			409,
			responseBody,
			"AI detection request conflicts with an existing idempotency key",
			cause
		);
	}

	public static RiskDetectionClientException httpError(int status, Throwable cause) {
		return httpError(status, null, cause);
	}

	public static RiskDetectionClientException httpError(
		int status,
		String responseBody,
		Throwable cause
	) {
		return new RiskDetectionClientException(
			Reason.HTTP_ERROR,
			status,
			responseBody,
			"AI detection request failed with HTTP status " + status,
			cause
		);
	}

	public static RiskDetectionClientException emptyResponse() {
		return new RiskDetectionClientException(
			Reason.EMPTY_RESPONSE,
			null,
			null,
			"AI detection response body is empty",
			null
		);
	}

	public static RiskDetectionClientException networkError(Throwable cause) {
		return new RiskDetectionClientException(
			Reason.NETWORK_ERROR,
			null,
			null,
			"AI detection server could not be reached",
			cause
		);
	}

	public Reason reason() {
		return reason;
	}

	public Integer httpStatus() {
		return httpStatus;
	}

	public String responseBody() {
		return responseBody;
	}

	public enum Reason {
		IDEMPOTENCY_CONFLICT,
		HTTP_ERROR,
		EMPTY_RESPONSE,
		NETWORK_ERROR
	}
}
