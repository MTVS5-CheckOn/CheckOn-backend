package com.checkon.detection.application;

import java.time.LocalDate;
import java.util.Objects;

public record DetectionExecutionKey(String value) {

	private static final String DELIMITER = ":";

	public DetectionExecutionKey {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}

	public static DetectionExecutionKey daily(String tenantAlias, LocalDate analysisDate) {
		if (tenantAlias == null || tenantAlias.isBlank()) {
			throw new IllegalArgumentException("tenantAlias must not be blank");
		}
		Objects.requireNonNull(analysisDate, "analysisDate must not be null");

		return new DetectionExecutionKey(
			tenantAlias + DELIMITER + analysisDate
		);
	}
}
