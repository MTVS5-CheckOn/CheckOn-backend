package com.checkon.problem.integration.kafka;

import java.time.Duration;

import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository.OutboxMessage;

public interface ProblemGenerationEventPublisher {
	void publish(OutboxMessage message, Duration timeout);
}
