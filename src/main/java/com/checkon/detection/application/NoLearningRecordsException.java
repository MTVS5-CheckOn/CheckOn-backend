package com.checkon.detection.application;

public class NoLearningRecordsException extends RuntimeException {

	public NoLearningRecordsException() {
		super("No learning records exist inside the approved analysis period");
	}
}
