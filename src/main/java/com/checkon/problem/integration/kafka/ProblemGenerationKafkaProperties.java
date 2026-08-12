package com.checkon.problem.integration.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "checkon.ai.problem-generation.kafka")
public record ProblemGenerationKafkaProperties(
	boolean enabled,
	@DefaultValue("checkon.ai.problem-generation.requests.v1") String requestTopic,
	@DefaultValue("checkon.ai.problem-generation.results.v1") String resultTopic,
	@DefaultValue("checkon.backend.problem-generation.results.dlt.v1") String deadLetterTopic,
	@DefaultValue("checkon-backend-problem-generation-v1") String consumerGroup,
	@DefaultValue("20") int outboxBatchSize,
	@DefaultValue("5") int outboxMaxAttempts,
	@DefaultValue("10s") Duration outboxPublishTimeout,
	@DefaultValue("1m") Duration staleClaimAfter
) {
	public ProblemGenerationKafkaProperties {
		requestTopic = requireText(requestTopic, "requestTopic");
		resultTopic = requireText(resultTopic, "resultTopic");
		deadLetterTopic = requireText(deadLetterTopic, "deadLetterTopic");
		consumerGroup = requireText(consumerGroup, "consumerGroup");
		if (outboxBatchSize < 1 || outboxBatchSize > 100) throw new IllegalArgumentException("outboxBatchSize must be 1..100");
		if (outboxMaxAttempts < 1 || outboxMaxAttempts > 20) throw new IllegalArgumentException("outboxMaxAttempts must be 1..20");
		if (outboxPublishTimeout == null || outboxPublishTimeout.isNegative() || outboxPublishTimeout.isZero())
			throw new IllegalArgumentException("outboxPublishTimeout must be positive");
		if (staleClaimAfter == null || staleClaimAfter.isNegative() || staleClaimAfter.isZero())
			throw new IllegalArgumentException("staleClaimAfter must be positive");
	}
	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value.trim();
	}
}
