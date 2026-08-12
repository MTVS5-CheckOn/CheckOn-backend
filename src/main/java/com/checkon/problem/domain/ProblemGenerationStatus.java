package com.checkon.problem.domain;

public enum ProblemGenerationStatus {
	QUEUED, DISPATCHED, RUNNING, SUCCEEDED, FAILED, CANCELLED, DELIVERY_FAILED;

	public boolean terminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED;
	}
}
