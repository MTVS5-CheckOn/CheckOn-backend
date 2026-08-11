package com.checkon.detection.integration.kafka;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/** AI-to-Backend business failure payload; transport failures use Kafka retry/DLT. */
public record AiDetectionFailure(
	String code,
	String message,
	Map<String, Object> detail,
	@JsonProperty("retryable") boolean retryable
) {
}
