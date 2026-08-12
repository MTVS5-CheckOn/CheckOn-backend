package com.checkon.detection.integration.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("checkon.kafka.risk-detection")
public record RiskDetectionKafkaProperties(
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
	public RiskDetectionKafkaProperties {
		if (isBlank(requestedTopic) || isBlank(completedTopic) || isBlank(failedTopic)) {
			throw new IllegalArgumentException("Risk detection Kafka topics must not be blank");
		}
		if (isBlank(consumerGroupId)) {
			throw new IllegalArgumentException("Risk detection Kafka consumerGroupId must not be blank");
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
