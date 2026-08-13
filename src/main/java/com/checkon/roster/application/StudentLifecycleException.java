package com.checkon.roster.application;

public class StudentLifecycleException extends RuntimeException {
	public enum Reason { INVALID_PRINCIPAL, NOT_FOUND, INVALID_STATE }

	private final Reason reason;

	private StudentLifecycleException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public static StudentLifecycleException of(Reason reason, String message) {
		return new StudentLifecycleException(reason, message);
	}
}
