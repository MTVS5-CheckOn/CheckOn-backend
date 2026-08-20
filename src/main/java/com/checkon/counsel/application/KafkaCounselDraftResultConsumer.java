package com.checkon.counsel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.infrastructure.kafka.CounselDraftOutboxRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;
import com.checkon.counsel.integration.kafka.CounselDraftKafkaEvent;
import com.checkon.counsel.integration.kafka.CounselDraftKafkaFailure;
import com.checkon.counsel.integration.kafka.CounselDraftKafkaOutcome;
import com.checkon.detection.infrastructure.kafka.AiTenantAliasService;
import com.checkon.detection.infrastructure.kafka.KafkaInboxRepository;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes counsel draft outcome events idempotently and applies them inside
 * the tenant boundary — mirrors
 * {@code detection.application.KafkaDetectionResultConsumer}, simplified for
 * counsel's single-attempt-per-job model (no run/attempt pair to reconcile).
 * {@code AiTenantAliasService} and {@code KafkaInboxRepository} are reused
 * cross-slice from detection: {@code ai_tenant_aliases}/{@code
 * kafka_inbox_events} are already shared, tenant-agnostic tables.
 */
@Service
public class KafkaCounselDraftResultConsumer {

	private static final Logger log = LoggerFactory.getLogger(KafkaCounselDraftResultConsumer.class);

	private final AiTenantAliasService tenantAliasService;
	private final KafkaInboxRepository inboxRepository;
	private final CounselDraftOutboxRepository outboxRepository;
	private final CounselDraftJobRepository jobRepository;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public KafkaCounselDraftResultConsumer(
		AiTenantAliasService tenantAliasService,
		KafkaInboxRepository inboxRepository,
		CounselDraftOutboxRepository outboxRepository,
		CounselDraftJobRepository jobRepository,
		TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.tenantAliasService = tenantAliasService;
		this.inboxRepository = inboxRepository;
		this.outboxRepository = outboxRepository;
		this.jobRepository = jobRepository;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public void consumeCompleted(String topic, String rawEvent) {
		ParsedEvent event = parse(rawEvent, CounselDraftKafkaEvent.COMPLETED);
		CounselDraftKafkaOutcome outcome = readPayload(event.payload(), CounselDraftKafkaOutcome.class);
		UUID teacherId = tenantAliasService.requireTeacherId(event.tenantAlias());
		if (!inboxRepository.recordIfAbsent(event.eventId(), topic, Instant.now(clock))) return;

		CounselDraftJobRepository.Job job = currentJob(teacherId, event);
		if (job == null) return; // event does not match a known, still-open job — nothing to apply

		jobRepository.setAiOutcome(
			teacherId, job.jobId(), outcome.jobId(), outcome.status(), outcome.executionId(), Instant.now(clock)
		);
	}

	@Transactional
	public void consumeFailed(String topic, String rawEvent) {
		ParsedEvent event = parse(rawEvent, CounselDraftKafkaEvent.FAILED);
		CounselDraftKafkaFailure failure = readPayload(event.payload(), CounselDraftKafkaFailure.class);
		UUID teacherId = tenantAliasService.requireTeacherId(event.tenantAlias());
		if (!inboxRepository.recordIfAbsent(event.eventId(), topic, Instant.now(clock))) return;

		CounselDraftJobRepository.Job job = currentJob(teacherId, event);
		if (job == null) return;

		requiredText(failure.code(), "payload.code");
		requiredText(failure.message(), "payload.message");
		jobRepository.setAiOutcome(teacherId, job.jobId(), null, "failed", null, Instant.now(clock));
		log.warn("Counsel draft job failed: teacherId={}, jobId={}, code={}", teacherId, job.jobId(), failure.code());
	}

	private CounselDraftJobRepository.Job currentJob(UUID teacherId, ParsedEvent event) {
		tenantContext.setCurrentTeacher(teacherId);
		var job = jobRepository.findByTeacherAndJobId(teacherId, event.correlationId().toString())
			.orElseThrow(() -> new IllegalArgumentException("Kafka event does not match a known counsel draft job"));
		if (!event.idempotencyKey().equals(job.idempotencyKey())) {
			throw new IllegalArgumentException("Kafka event idempotency_key does not match the job");
		}
		if (!outboxRepository.existsRequestedEvent(event.causationId(), job.id())) {
			throw new IllegalArgumentException("causation_id is not the original request event for this job");
		}
		return job;
	}

	private ParsedEvent parse(String rawEvent, String expectedType) {
		try {
			JsonNode root = objectMapper.readTree(rawEvent);
			String eventType = requiredText(text(root, "event_type"), "event_type");
			if (!expectedType.equals(eventType)) {
				throw new IllegalArgumentException("Unexpected counsel draft event_type: " + eventType);
			}
			if (!CounselDraftKafkaEvent.SCHEMA_VERSION.equals(text(root, "schema_version"))) {
				throw new IllegalArgumentException("Unsupported counsel draft schema_version");
			}
			JsonNode payload = root.get("payload");
			if (payload == null || payload.isNull()) {
				throw new IllegalArgumentException("Kafka event payload must not be null");
			}
			return new ParsedEvent(
				uuid(root, "event_id"),
				uuid(root, "correlation_id"),
				uuid(root, "causation_id"),
				requiredText(text(root, "tenant_alias"), "tenant_alias"),
				requiredText(text(root, "idempotency_key"), "idempotency_key"),
				payload
			);
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("Kafka event JSON is invalid", exception);
		}
	}

	private <T> T readPayload(JsonNode payload, Class<T> type) {
		try {
			return objectMapper.treeToValue(payload, type);
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("Kafka event payload is invalid", exception);
		}
	}

	private UUID uuid(JsonNode root, String field) {
		try {
			return UUID.fromString(requiredText(text(root, field), field));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(field + " must be a UUID", exception);
		}
	}

	private String text(JsonNode root, String field) {
		JsonNode value = root.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private String requiredText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	private record ParsedEvent(
		UUID eventId,
		UUID correlationId,
		UUID causationId,
		String tenantAlias,
		String idempotencyKey,
		JsonNode payload
	) {
	}
}
