package com.checkon.detection.integration.ai;

public record AiDetectionRequestHeaders(
	String tenantId,
	String requestId,
	String idempotencyKey
) {

	public AiDetectionRequestHeaders {
		requireText(tenantId, "tenantId");
		requireText(requestId, "requestId");
		requireText(idempotencyKey, "idempotencyKey");
	}

	private static void requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
	}
}
