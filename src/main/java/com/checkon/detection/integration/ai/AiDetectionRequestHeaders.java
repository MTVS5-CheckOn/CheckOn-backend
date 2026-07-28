package com.checkon.detection.integration.ai;

import com.checkon.detection.application.DetectionExecutionKey;

public record AiDetectionRequestHeaders(
	String tenantId,
	String requestId,
	DetectionExecutionKey idempotencyKey
) {

	public AiDetectionRequestHeaders {
		requireText(tenantId, "tenantId");
		requireText(requestId, "requestId");
		if (idempotencyKey == null) {
			throw new IllegalArgumentException("idempotencyKey must not be null");
		}
	}

	private static void requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
	}
}
