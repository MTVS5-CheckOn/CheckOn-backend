package com.checkon.roster.application;

public class StudentPersonalInformationException extends RuntimeException {
	public enum Reason { INVALID_PRINCIPAL, NOT_FOUND, INVALID_NAME }

	private final Reason reason;

	private StudentPersonalInformationException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public static StudentPersonalInformationException of(Reason reason, String message) {
		return new StudentPersonalInformationException(reason, message);
	}
}

