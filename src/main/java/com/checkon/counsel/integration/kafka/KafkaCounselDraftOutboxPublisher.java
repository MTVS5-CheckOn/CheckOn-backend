package com.checkon.counsel.integration.kafka;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.checkon.counsel.infrastructure.kafka.CounselDraftOutboxRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;

/** Delivers persisted Outbox events with at-least-once semantics — mirrors {@code detection.integration.kafka.KafkaOutboxPublisher}. */
@Component
@ConditionalOnProperty(prefix = "checkon.kafka.counsel-draft", name = "enabled", havingValue = "true")
public class KafkaCounselDraftOutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(KafkaCounselDraftOutboxPublisher.class);

	private final CounselDraftOutboxRepository outboxRepository;
	private final CounselDraftJobRepository jobRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final CounselDraftKafkaProperties properties;
	private final Clock clock;

	public KafkaCounselDraftOutboxPublisher(
		CounselDraftOutboxRepository outboxRepository,
		CounselDraftJobRepository jobRepository,
		KafkaTemplate<String, String> kafkaTemplate,
		CounselDraftKafkaProperties properties,
		Clock clock
	) {
		this.outboxRepository = outboxRepository;
		this.jobRepository = jobRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${checkon.kafka.counsel-draft.outbox-poll-delay}")
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
		for (CounselDraftOutboxRepository.ClaimedEvent event : claimed) {
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

	private void reschedule(CounselDraftOutboxRepository.ClaimedEvent event, Exception exception) {
		Instant nextAttempt = Instant.now(clock).plus(properties.outboxRetryDelay());
		outboxRepository.reschedule(
			event.id(),
			event.publishAttempts(),
			properties.outboxMaxAttempts(),
			nextAttempt,
			exception.getClass().getSimpleName()
		);
		if (event.publishAttempts() >= properties.outboxMaxAttempts()) {
			// The request never left this service, so no completion event will ever
			// arrive for it either — leaving job_phase at "queued" forever would strand
			// the teacher's polling. Fail it locally instead.
			jobRepository.updateKnownPhase(event.teacherId(), event.jobId().toString(), "failed", Instant.now(clock));
		}
		log.warn("Counsel draft Kafka Outbox publish failed: eventId={}, attempt={}",
			event.id(), event.publishAttempts());
	}
}
