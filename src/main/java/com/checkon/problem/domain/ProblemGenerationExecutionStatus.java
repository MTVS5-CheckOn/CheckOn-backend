package com.checkon.problem.domain;

public enum ProblemGenerationExecutionStatus {
	QUEUED,
	DISPATCHED,
	RUNNING,
	SUCCEEDED,
	FAILED,
	CANCELLED,
	TIMED_OUT,
	DELIVERY_FAILED,
	REJECTED_INSUFFICIENT;

	public boolean terminal() {
		return switch (this) {
			case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, DELIVERY_FAILED, REJECTED_INSUFFICIENT -> true;
			case QUEUED, DISPATCHED, RUNNING -> false;
		};
	}

	public boolean failureForParentAggregation() {
		return this == FAILED || this == TIMED_OUT || this == DELIVERY_FAILED;
	}
}
