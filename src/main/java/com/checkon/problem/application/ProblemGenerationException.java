package com.checkon.problem.application;

public class ProblemGenerationException extends RuntimeException {
	private final Reason reason;

	private ProblemGenerationException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public static ProblemGenerationException invalidPrincipal() { return new ProblemGenerationException(Reason.INVALID_PRINCIPAL, "authenticated teacher profile is required"); }
	public static ProblemGenerationException inaccessibleTarget() { return new ProblemGenerationException(Reason.TARGET_NOT_FOUND, "problem target is missing or inaccessible"); }
	public static ProblemGenerationException invalidRequest(String message) { return new ProblemGenerationException(Reason.INVALID_REQUEST, message); }
	public static ProblemGenerationException notFound() { return new ProblemGenerationException(Reason.REQUEST_NOT_FOUND, "problem generation request is missing or inaccessible"); }
	public static ProblemGenerationException idempotencyConflict() { return new ProblemGenerationException(Reason.IDEMPOTENCY_CONFLICT, "idempotency key was already used with another request"); }
	public Reason reason() { return reason; }

	public enum Reason { INVALID_PRINCIPAL, TARGET_NOT_FOUND, INVALID_REQUEST, REQUEST_NOT_FOUND, IDEMPOTENCY_CONFLICT }
}
