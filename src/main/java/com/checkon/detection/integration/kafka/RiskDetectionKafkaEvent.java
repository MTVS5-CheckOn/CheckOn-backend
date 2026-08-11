package com.checkon.detection.integration.kafka;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Versioned envelope shared by every Backend-to-AI and AI-to-Backend event. */
public record RiskDetectionKafkaEvent<T>(
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
	public static final String REQUESTED = "risk-detection.requested";
	public static final String COMPLETED = "risk-detection.completed";
	public static final String FAILED = "risk-detection.failed";
}
