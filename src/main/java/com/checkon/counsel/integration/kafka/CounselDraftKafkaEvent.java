package com.checkon.counsel.integration.kafka;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Versioned envelope for counsel draft Kafka events — the same 13-field
 * shape as {@code detection.integration.kafka.RiskDetectionKafkaEvent}
 * ("envelope RiskDetectionOutcomeEvent 13필드 그대로", AI-A 2026-08-20).
 * {@code run_id}/{@code attempt_id} carry the backend-minted job id twice:
 * counsel has no multi-attempt run concept in v1 (one inquiry = one job), so
 * there is nothing else to put there — a fresh id is not minted per attempt.
 */
public record CounselDraftKafkaEvent<T>(
	@JsonProperty("event_id") UUID eventId,
	@JsonProperty("event_type") String eventType,
	@JsonProperty("schema_version") String schemaVersion,
	@JsonProperty("correlation_id") UUID correlationId,
	@JsonProperty("causation_id") UUID causationId,
	@JsonProperty("tenant_alias") String tenantAlias,
	@JsonProperty("run_id") UUID runId,
	@JsonProperty("attempt_id") UUID attemptId,
	@JsonProperty("request_id") String requestId,
	@JsonProperty("idempotency_key") String idempotencyKey,
	@JsonProperty("snapshot_hash") String snapshotHash,
	@JsonProperty("occurred_at") Instant occurredAt,
	T payload
) {
	public static final String SCHEMA_VERSION = "1.0";
	public static final String REQUESTED = "counsel-draft.requested";
	public static final String COMPLETED = "counsel-draft.completed";
	public static final String FAILED = "counsel-draft.failed";
}
