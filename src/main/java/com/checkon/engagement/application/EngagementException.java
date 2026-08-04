package com.checkon.engagement.application;

public class EngagementException extends RuntimeException {
	public enum Reason {
		INVALID_PRINCIPAL,
		NOT_FOUND,
		INVALID_STATE,
		INVALID_REQUEST,
		ACTIVE_REMINDER_EXISTS
	}

	private final Reason reason;

	private EngagementException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public static EngagementException of(Reason reason, String message) {
		return new EngagementException(reason, message);
	}
}
