package com.checkon.counsel.application;

public class GuardianLabelSuggestionException extends RuntimeException {

	private final Reason reason;

	private GuardianLabelSuggestionException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public static GuardianLabelSuggestionException invalidPrincipal() {
		return new GuardianLabelSuggestionException(Reason.INVALID_PRINCIPAL, "teacher principal is required", null);
	}

	public static GuardianLabelSuggestionException targetNotFound() {
		return new GuardianLabelSuggestionException(Reason.TARGET_NOT_FOUND, "parent was not found", null);
	}

	public static GuardianLabelSuggestionException upstream(Throwable cause) {
		return new GuardianLabelSuggestionException(Reason.UPSTREAM_FAILURE, "guardian label AI failed", cause);
	}

	public static GuardianLabelSuggestionException suggestionNotFound() {
		return new GuardianLabelSuggestionException(Reason.SUGGESTION_NOT_FOUND, "guardian label suggestion was not found", null);
	}

	public static GuardianLabelSuggestionException decisionConflict() {
		return new GuardianLabelSuggestionException(Reason.DECISION_CONFLICT, "guardian label suggestion was already decided differently", null);
	}

	public static GuardianLabelSuggestionException invalidDecision() {
		return new GuardianLabelSuggestionException(Reason.INVALID_DECISION, "guardian label decision is invalid", null);
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		INVALID_PRINCIPAL,
		TARGET_NOT_FOUND,
		SUGGESTION_NOT_FOUND,
		DECISION_CONFLICT,
		INVALID_DECISION,
		UPSTREAM_FAILURE
	}
}
