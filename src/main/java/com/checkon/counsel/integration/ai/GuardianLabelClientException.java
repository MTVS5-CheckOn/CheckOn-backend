package com.checkon.counsel.integration.ai;

public class GuardianLabelClientException extends RuntimeException {

	private final Reason reason;
	private final Integer httpStatus;
	private final String responseBody;

	private GuardianLabelClientException(
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

	static GuardianLabelClientException http(Reason reason, int status, String body, Throwable cause) {
		return new GuardianLabelClientException(reason, status, body, "guardian label AI returned HTTP " + status, cause);
	}

	static GuardianLabelClientException emptyResponse() {
		return new GuardianLabelClientException(Reason.EMPTY_RESPONSE, null, null, "guardian label AI response is empty", null);
	}

	static GuardianLabelClientException invalidResponse(String message) {
		return new GuardianLabelClientException(Reason.INVALID_RESPONSE, 200, null, message, null);
	}

	public static GuardianLabelClientException network(Throwable cause) {
		return new GuardianLabelClientException(Reason.NETWORK_ERROR, null, null, "guardian label AI could not be reached", cause);
	}

	public static GuardianLabelClientException historyAllBlocked() {
		return new GuardianLabelClientException(
			Reason.INTERNAL_ERROR, 500, "{\"error\":{\"code\":\"history_all_blocked\"}}",
			"guardian label history was entirely blocked", null
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
		INVALID_REQUEST,
		INTERNAL_ERROR,
		UPSTREAM_DOWN,
		TIMEOUT,
		HTTP_ERROR,
		EMPTY_RESPONSE,
		INVALID_RESPONSE,
		NETWORK_ERROR
	}
}
