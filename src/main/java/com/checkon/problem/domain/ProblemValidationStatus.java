package com.checkon.problem.domain;

public enum ProblemValidationStatus {
	PASSED,
	REVIEW_REQUIRED,
	UNVERIFIABLE,
	EXCLUDED;

	public boolean publishable() {
		return this == PASSED || this == REVIEW_REQUIRED;
	}
}
