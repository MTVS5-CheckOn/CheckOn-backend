package com.checkon.counsel.integration.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * risk-detection topic convention, per team decision on 2026-08-20:
 * {@code checkon.counsel-draft.requested/completed/failed.v1} — 3 topics, no
 * "ai." prefix, past-participle names. The adapter's own consumer group for
 * the requested topic is fixed by the AI contract
 * ({@code checkon-ai-adapter-counsel-draft-v1}); {@code consumerGroupId} here
 * is the backend's own group for consuming completed/failed.
 */
@ConfigurationProperties("checkon.kafka.counsel-draft")
public record CounselDraftKafkaProperties(
	boolean enabled,
	String requestedTopic,
	String completedTopic,
	String failedTopic,
	String consumerGroupId,
	Duration outboxPollDelay,
	Duration outboxLockTimeout,
	Duration outboxRetryDelay,
	Duration producerSendTimeout,
	int outboxBatchSize,
	int outboxMaxAttempts
) {
	public CounselDraftKafkaProperties {
		if (isBlank(requestedTopic) || isBlank(completedTopic) || isBlank(failedTopic)) {
			throw new IllegalArgumentException("Counsel draft Kafka topics must not be blank");
		}
		if (isBlank(consumerGroupId)) {
			throw new IllegalArgumentException("Counsel draft Kafka consumer group id must not be blank");
		}
		outboxPollDelay = positive(outboxPollDelay, "outboxPollDelay");
		outboxLockTimeout = positive(outboxLockTimeout, "outboxLockTimeout");
		outboxRetryDelay = positive(outboxRetryDelay, "outboxRetryDelay");
		producerSendTimeout = positive(producerSendTimeout, "producerSendTimeout");
		if (outboxBatchSize < 1) {
			throw new IllegalArgumentException("outboxBatchSize must be at least 1");
		}
		if (outboxMaxAttempts < 1) {
			throw new IllegalArgumentException("outboxMaxAttempts must be at least 1");
		}
	}

	private static Duration positive(Duration value, String name) {
		if (value == null || value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
