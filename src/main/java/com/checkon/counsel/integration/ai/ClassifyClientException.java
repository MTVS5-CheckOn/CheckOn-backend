package com.checkon.counsel.integration.ai;

/**
 * Reason mirrors classify/confirmations' actual HTTP error codes (§3-3 of the
 * 2026-08-20 classify contract) — unlike counsel, this endpoint distinguishes
 * a retryable upstream fault ({@code TIMEOUT}/{@code UPSTREAM_DOWN}) from a
 * non-retryable one ({@code INTERNAL_ERROR}); retrying a 500 fails the same way.
 */
public class ClassifyClientException extends RuntimeException {

	private final Reason reason;
	private final Integer httpStatus;
	private final String responseBody;

	private ClassifyClientException(
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

	public static ClassifyClientException invalidRequest(String responseBody, Throwable cause) {
		return new ClassifyClientException(Reason.INVALID_REQUEST, 400, responseBody, "classify request was rejected as invalid", cause);
	}

	public static ClassifyClientException notFound(String responseBody, Throwable cause) {
		return new ClassifyClientException(Reason.NOT_FOUND, 404, responseBody, "no stored classification for this inquiry_ref", cause);
	}

	public static ClassifyClientException timeout(String responseBody, Throwable cause) {
		return new ClassifyClientException(Reason.TIMEOUT, 504, responseBody, "classify AI response timed out", cause);
	}

	public static ClassifyClientException upstreamDown(String responseBody, Throwable cause) {
		return new ClassifyClientException(Reason.UPSTREAM_DOWN, 503, responseBody, "classify AI vendor is unavailable", cause);
	}

	public static ClassifyClientException internalError(String responseBody, Throwable cause) {
		return new ClassifyClientException(Reason.INTERNAL_ERROR, 500, responseBody, "classify AI server failed internally", cause);
	}

	public static ClassifyClientException httpError(int status, String responseBody, Throwable cause) {
		return new ClassifyClientException(Reason.HTTP_ERROR, status, responseBody, "classify AI request failed with HTTP status " + status, cause);
	}

	public static ClassifyClientException emptyResponse() {
		return new ClassifyClientException(Reason.EMPTY_RESPONSE, null, null, "classify AI response body is empty", null);
	}

	public static ClassifyClientException networkError(Throwable cause) {
		return new ClassifyClientException(Reason.NETWORK_ERROR, null, null, "classify AI server could not be reached", cause);
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
		INVALID_REQUEST,
		NOT_FOUND,
		TIMEOUT,
		UPSTREAM_DOWN,
		INTERNAL_ERROR,
		HTTP_ERROR,
		EMPTY_RESPONSE,
		NETWORK_ERROR
	}
}
