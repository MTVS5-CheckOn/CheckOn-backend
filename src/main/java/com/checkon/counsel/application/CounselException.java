package com.checkon.counsel.application;

public class CounselException extends RuntimeException {

	private final Reason reason;

	private CounselException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public static CounselException invalidPrincipal() {
		return new CounselException(Reason.INVALID_PRINCIPAL, "authenticated teacher profile is required", null);
	}

	public static CounselException invalidRequest(String message) {
		return new CounselException(Reason.INVALID_REQUEST, message, null);
	}

	public static CounselException idempotencyConflict() {
		return new CounselException(Reason.IDEMPOTENCY_CONFLICT, "idempotency key was already used with another request", null);
	}

	public static CounselException jobNotFound() {
		return new CounselException(Reason.JOB_NOT_FOUND, "counsel draft job is missing or inaccessible", null);
	}

	public static CounselException targetNotFound() {
		return new CounselException(Reason.TARGET_NOT_FOUND, "student or class is missing or inaccessible", null);
	}

	public static CounselException upstreamUnavailable(Throwable cause) {
		return new CounselException(Reason.UPSTREAM_UNAVAILABLE, "counsel AI server is unavailable", cause);
	}

	/** Retrying will not help — the AI side has a bug, not a transient fault. */
	public static CounselException upstreamInternalError(Throwable cause) {
		return new CounselException(Reason.UPSTREAM_INTERNAL_ERROR, "counsel AI server failed internally", cause);
	}

	public static CounselException classificationNotFound() {
		return new CounselException(Reason.CLASSIFICATION_NOT_FOUND, "no stored classification for this inquiry_ref", null);
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		INVALID_PRINCIPAL, INVALID_REQUEST, IDEMPOTENCY_CONFLICT, JOB_NOT_FOUND, TARGET_NOT_FOUND,
		UPSTREAM_UNAVAILABLE, UPSTREAM_INTERNAL_ERROR, CLASSIFICATION_NOT_FOUND
	}
}
