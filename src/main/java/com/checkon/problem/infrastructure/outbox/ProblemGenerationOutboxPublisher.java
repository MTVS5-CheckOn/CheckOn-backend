package com.checkon.problem.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.checkon.problem.integration.kafka.ProblemGenerationEventPublisher;
import com.checkon.problem.integration.kafka.ProblemGenerationKafkaProperties;

@Component
@ConditionalOnProperty(prefix = "checkon.ai.problem-generation.kafka", name = "enabled", havingValue = "true")
public class ProblemGenerationOutboxPublisher {
	private static final Logger log = LoggerFactory.getLogger(ProblemGenerationOutboxPublisher.class);
	private final ProblemGenerationOutboxCoordinator coordinator;
	private final ProblemGenerationEventPublisher publisher;
	private final ProblemGenerationKafkaProperties properties;
	public ProblemGenerationOutboxPublisher(ProblemGenerationOutboxCoordinator coordinator,
		ProblemGenerationEventPublisher publisher, ProblemGenerationKafkaProperties properties) {
		this.coordinator = coordinator; this.publisher = publisher; this.properties = properties;
	}
	@Scheduled(fixedDelayString = "${checkon.ai.problem-generation.kafka.outbox-poll-delay:1s}")
	public void publishPending() {
		for (var teacherId : coordinator.teacherIds()) {
			for (var message : coordinator.claim(teacherId)) {
				try { publisher.publish(message, properties.outboxPublishTimeout()); coordinator.published(message); }
				catch (RuntimeException failure) {
					coordinator.failed(message, failure);
					log.warn("Problem generation outbox publish failed: eventId={}, requestId={}, attempt={}",
						message.id(), message.requestId(), message.attemptCount());
				}
			}
		}
	}
}
