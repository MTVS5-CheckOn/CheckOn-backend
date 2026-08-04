package com.checkon.dashboard.application;

public class FutureBriefingDateException extends RuntimeException {
	public FutureBriefingDateException() {
		super("Future briefing dates cannot be queried.");
	}
}
