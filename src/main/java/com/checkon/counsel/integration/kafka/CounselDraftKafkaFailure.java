package com.checkon.counsel.integration.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

/** AI-to-Backend business failure payload; transport failures use Kafka retry/DLT instead. */
public record CounselDraftKafkaFailure(
	String code,
	String message,
	Object detail,
	@JsonProperty("retryable") boolean retryable
) {
}
