package com.checkon.detection.application;

public class DetectionExecutionException extends RuntimeException {

	public DetectionExecutionException(String message) {
		super(message);
	}

	public DetectionExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
