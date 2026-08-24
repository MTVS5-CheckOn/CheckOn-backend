package com.checkon.counsel.integration.ai;

public class CounselClientException extends RuntimeException {

	private final Reason reason;
	private final Integer httpStatus;
	private final String responseBody;

	private CounselClientException(
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

	public static CounselClientException idempotencyConflict(String responseBody, Throwable cause) {
		return new CounselClientException(
			Reason.IDEMPOTENCY_CONFLICT,
			409,
			responseBody,
			"counsel AI request conflicts with an existing idempotency key",
			cause
		);
	}

	public static CounselClientException notFound(String responseBody, Throwable cause) {
		return new CounselClientException(
			Reason.NOT_FOUND,
			404,
			responseBody,
			"counsel AI job was not found",
			cause
		);
	}

	public static CounselClientException httpError(int status, String responseBody, Throwable cause) {
		return new CounselClientException(
			Reason.HTTP_ERROR,
			status,
			responseBody,
			"counsel AI request failed with HTTP status " + status,
			cause
		);
	}

	public static CounselClientException emptyResponse() {
		return new CounselClientException(
			Reason.EMPTY_RESPONSE,
			null,
			null,
			"counsel AI response body is empty",
			null
		);
	}

	public static CounselClientException networkError(Throwable cause) {
		return new CounselClientException(
			Reason.NETWORK_ERROR,
			null,
			null,
			"counsel AI server could not be reached",
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
		NOT_FOUND,
		HTTP_ERROR,
		EMPTY_RESPONSE,
		NETWORK_ERROR
	}
}
