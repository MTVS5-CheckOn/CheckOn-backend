package com.checkon.problem.domain;

public enum ProblemGenerationStatus {
	QUEUED, DISPATCHED, RUNNING, SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCELLED, DELIVERY_FAILED;

	public boolean terminal() {
		return this == SUCCEEDED || this == PARTIAL_SUCCESS || this == FAILED || this == CANCELLED;
	}
}
