package com.checkon.problem.application;

public class ProblemGenerationException extends RuntimeException {
	private final Reason reason;
	private final String detailReason;
	private final Integer currentRevisionNo;

	private ProblemGenerationException(Reason reason, String message) {
		this(reason,message,null,null);
	}
	private ProblemGenerationException(Reason reason, String message, String detailReason, Integer currentRevisionNo) {
		super(message);
		this.reason = reason;
		this.detailReason = detailReason;
		this.currentRevisionNo = currentRevisionNo;
	}

	public static ProblemGenerationException invalidPrincipal() { return new ProblemGenerationException(Reason.INVALID_PRINCIPAL, "authenticated teacher profile is required"); }
	public static ProblemGenerationException inaccessibleTarget() { return new ProblemGenerationException(Reason.TARGET_NOT_FOUND, "problem target is missing or inaccessible"); }
	public static ProblemGenerationException invalidRequest(String message) { return new ProblemGenerationException(Reason.INVALID_REQUEST, message); }
	public static ProblemGenerationException notFound() { return new ProblemGenerationException(Reason.REQUEST_NOT_FOUND, "problem generation request is missing or inaccessible"); }
	public static ProblemGenerationException idempotencyConflict() { return new ProblemGenerationException(Reason.IDEMPOTENCY_CONFLICT, "idempotency key was already used with another request"); }
	public static ProblemGenerationException invalidState(String message) { return new ProblemGenerationException(Reason.INVALID_STATE, message); }
	public static ProblemGenerationException revisionConflict(String reason, Integer currentRevisionNo) {
		return new ProblemGenerationException(Reason.REVISION_CONFLICT,
			"problem revision conflicts with the current slot state",reason,currentRevisionNo);
	}
	public Reason reason() { return reason; }
	public String detailReason() { return detailReason; }
	public Integer currentRevisionNo() { return currentRevisionNo; }

	public enum Reason { INVALID_PRINCIPAL, TARGET_NOT_FOUND, INVALID_REQUEST, REQUEST_NOT_FOUND,
		IDEMPOTENCY_CONFLICT, REVISION_CONFLICT, INVALID_STATE }
}
