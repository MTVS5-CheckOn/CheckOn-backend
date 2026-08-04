package com.checkon.learning.application;

public class LearningRecordRegistrationException extends RuntimeException {
	private final Reason reason;

	private LearningRecordRegistrationException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public static LearningRecordRegistrationException invalidTeacherPrincipal() {
		return new LearningRecordRegistrationException(
			Reason.INVALID_TEACHER_PRINCIPAL,
			"Authenticated teacher profile is required"
		);
	}

	public static LearningRecordRegistrationException inaccessibleStudent() {
		return new LearningRecordRegistrationException(
			Reason.INACCESSIBLE_STUDENT,
			"Student is not accessible to the authenticated teacher"
		);
	}

	public static LearningRecordRegistrationException inaccessibleClassGroup() {
		return new LearningRecordRegistrationException(
			Reason.INACCESSIBLE_CLASS_GROUP,
			"Class group is not accessible to the authenticated teacher"
		);
	}

	public static LearningRecordRegistrationException invalidRecord(
		IllegalArgumentException cause
	) {
		LearningRecordRegistrationException exception =
			new LearningRecordRegistrationException(
				Reason.INVALID_RECORD,
				"Learning record values are invalid"
			);
		exception.initCause(cause);
		return exception;
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		INVALID_TEACHER_PRINCIPAL,
		INACCESSIBLE_STUDENT,
		INACCESSIBLE_CLASS_GROUP,
		INVALID_RECORD
	}
}
