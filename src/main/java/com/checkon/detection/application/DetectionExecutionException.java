package com.checkon.detection.application;

public class DetectionExecutionException extends RuntimeException {

	private final Reason reason;

	private DetectionExecutionException(
		Reason reason,
		String message,
		Throwable cause
	) {
		super(message, cause);
		this.reason = reason;
	}

	public static DetectionExecutionException runNotFound() {
		return new DetectionExecutionException(
			Reason.RUN_NOT_FOUND,
			"Detection run was not found inside the teacher boundary",
			null
		);
	}

	public static DetectionExecutionException tenantMismatch() {
		return new DetectionExecutionException(
			Reason.RUN_NOT_FOUND,
			"Detection run was not found inside the tenant boundary",
			null
		);
	}

	public static DetectionExecutionException invalidSnapshot(Throwable cause) {
		return new DetectionExecutionException(
			Reason.SNAPSHOT_PAYLOAD_INVALID,
			"Stored detection snapshot payload is invalid",
			cause
		);
	}

	public static DetectionExecutionException responseProcessing(Throwable cause) {
		return new DetectionExecutionException(
			Reason.RESPONSE_PROCESSING_ERROR,
			"AI detection response could not be processed",
			cause
		);
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		RUN_NOT_FOUND,
		SNAPSHOT_PAYLOAD_INVALID,
		RESPONSE_PROCESSING_ERROR
	}
}
