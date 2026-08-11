package com.checkon.detection.integration.kafka;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.checkon.detection.application.DetectionAttemptCoordinator;
import com.checkon.detection.infrastructure.kafka.KafkaOutboxRepository;

/** Delivers persisted Outbox events with at-least-once semantics. */
@Component
@ConditionalOnProperty(
	prefix = "checkon.kafka.risk-detection",
	name = "enabled",
	havingValue = "true"
)
public class KafkaOutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

	private final KafkaOutboxRepository outboxRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final RiskDetectionKafkaProperties properties;
	private final DetectionAttemptCoordinator attemptCoordinator;
	private final Clock clock;

	public KafkaOutboxPublisher(
		KafkaOutboxRepository outboxRepository,
		KafkaTemplate<String, String> kafkaTemplate,
		RiskDetectionKafkaProperties properties,
		DetectionAttemptCoordinator attemptCoordinator,
		Clock clock
	) {
		this.outboxRepository = outboxRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
		this.attemptCoordinator = attemptCoordinator;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${checkon.kafka.risk-detection.outbox-poll-delay}")
	public void publishScheduled() {
		publishDue();
	}

	public int publishDue() {
		Instant now = Instant.now(clock);
		var claimed = outboxRepository.claimDue(
			now,
			now.minus(properties.outboxLockTimeout()),
			properties.outboxBatchSize()
		);
		int published = 0;
		for (KafkaOutboxRepository.ClaimedEvent event : claimed) {
			try {
				kafkaTemplate.send(event.topic(), event.messageKey(), event.payload())
					.get(properties.producerSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
				outboxRepository.markPublished(event.id(), Instant.now(clock));
				published++;
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				reschedule(event, exception);
				break;
			}
			catch (Exception exception) {
				reschedule(event, exception);
			}
		}
		return published;
	}

	private void reschedule(KafkaOutboxRepository.ClaimedEvent event, Exception exception) {
		Instant nextAttempt = Instant.now(clock).plus(properties.outboxRetryDelay());
		outboxRepository.reschedule(
			event.id(),
			event.publishAttempts(),
			properties.outboxMaxAttempts(),
			nextAttempt,
			exception.getClass().getSimpleName()
		);
		if (event.publishAttempts() >= properties.outboxMaxAttempts()) {
			attemptCoordinator.fail(
				event.teacherId(),
				event.runId(),
				event.attemptId(),
				null,
				"KAFKA_PUBLISH_FAILED",
				Instant.now(clock)
			);
		}
		log.warn("Risk detection Kafka Outbox publish failed: eventId={}, attempt={}",
			event.id(), event.publishAttempts());
	}
}
