package com.checkon.engagement.application;

public class TodoException extends RuntimeException {
	public enum Reason { INVALID_PRINCIPAL, NOT_FOUND, INVALID_UPDATE, INVALID_STATE }
	private final Reason reason;
	private TodoException(Reason reason, String message) { super(message); this.reason = reason; }
	public Reason reason() { return reason; }
	public static TodoException of(Reason reason, String message) { return new TodoException(reason, message); }
}

