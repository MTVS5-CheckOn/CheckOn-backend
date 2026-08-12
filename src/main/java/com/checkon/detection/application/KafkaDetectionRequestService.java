package com.checkon.detection.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.infrastructure.kafka.KafkaOutboxRepository;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaEvent;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Persists the request attempt and its Kafka Outbox event atomically. */
@Service
public class KafkaDetectionRequestService {

	private final DetectionAttemptCoordinator attemptCoordinator;
	private final DetectionIdGenerator idGenerator;
	private final KafkaOutboxRepository outboxRepository;
	private final EntityManager entityManager;
	private final RiskDetectionKafkaProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public KafkaDetectionRequestService(
		DetectionAttemptCoordinator attemptCoordinator,
		DetectionIdGenerator idGenerator,
		KafkaOutboxRepository outboxRepository,
		EntityManager entityManager,
		RiskDetectionKafkaProperties properties,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.attemptCoordinator = attemptCoordinator;
		this.idGenerator = idGenerator;
		this.outboxRepository = outboxRepository;
		this.entityManager = entityManager;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public EnqueuedDetectionRequest enqueue(
		UUID teacherId,
		String tenantAlias,
		UUID runId
	) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(runId, "runId must not be null");
		if (tenantAlias == null || tenantAlias.isBlank()) {
			throw new IllegalArgumentException("tenantAlias must not be blank");
		}

		Instant now = Instant.now(clock);
		DetectionAttemptCoordinator.StartedDetectionAttempt attempt =
			attemptCoordinator.start(teacherId, tenantAlias, runId, now);
		// JDBC Outbox insert has an FK to the new JPA attempt. Flush in the same
		// transaction so PostgreSQL can validate that FK without splitting atomicity.
		entityManager.flush();
		UUID eventId = idGenerator.nextIds(1).getFirst();
		AiDetectionRequest request = readSnapshot(attempt.snapshotPayload());
		RiskDetectionKafkaEvent<AiDetectionRequest> event = new RiskDetectionKafkaEvent<>(
			eventId,
			RiskDetectionKafkaEvent.REQUESTED,
			RiskDetectionKafkaEvent.SCHEMA_VERSION,
			runId,
			eventId,
			tenantAlias,
			runId,
			attempt.attemptId(),
			attempt.requestId(),
			attempt.idempotencyKey(),
			attempt.snapshotHash(),
			now,
			request
		);
		outboxRepository.insert(new KafkaOutboxRepository.NewEvent(
			eventId,
			teacherId,
			runId,
			attempt.attemptId(),
			properties.requestedTopic(),
			tenantAlias,
			write(event),
			now
		));
		return new EnqueuedDetectionRequest(runId, attempt.attemptId(),
			attempt.attemptNumber(), eventId);
	}

	private AiDetectionRequest readSnapshot(String snapshotPayload) {
		try {
			return objectMapper.readValue(snapshotPayload, AiDetectionRequest.class);
		}
		catch (JacksonException exception) {
			throw DetectionExecutionException.invalidSnapshot(exception);
		}
	}

	private String write(RiskDetectionKafkaEvent<AiDetectionRequest> event) {
		try {
			return objectMapper.writeValueAsString(event);
		}
		catch (JacksonException exception) {
			throw DetectionExecutionException.invalidSnapshot(exception);
		}
	}

	public record EnqueuedDetectionRequest(
		UUID runId,
		UUID attemptId,
		int attemptNumber,
		UUID eventId
	) {
	}
}
