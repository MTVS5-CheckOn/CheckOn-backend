package com.checkon.detection.application;

public class DetectionRunAlreadyRunningException extends IllegalStateException {

	public DetectionRunAlreadyRunningException() {
		super("Detection run is already being executed");
	}
}
