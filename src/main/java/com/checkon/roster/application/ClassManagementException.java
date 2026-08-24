package com.checkon.roster.application;

public class ClassManagementException extends RuntimeException {
	public enum Reason {
		INVALID_PRINCIPAL,
		NOT_FOUND,
		INVALID_REQUEST,
		INVALID_STATE
	}

	private final Reason reason;

	private ClassManagementException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public static ClassManagementException of(Reason reason, String message) {
		return new ClassManagementException(reason, message);
	}
}
