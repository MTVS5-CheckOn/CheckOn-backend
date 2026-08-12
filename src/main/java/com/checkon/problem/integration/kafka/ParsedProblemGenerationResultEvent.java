package com.checkon.problem.integration.kafka;

import java.time.Instant;
import java.util.UUID;

import com.checkon.problem.domain.ProblemGenerationStatus;

public record ParsedProblemGenerationResultEvent(
	UUID eventId, String eventType, String schemaVersion, UUID requestId,
	String tenantAlias, ProblemGenerationStatus status, String jobId,
	String executionId, String setId, String resultStatus, String errorCode,
	String resultPayload, String versionsPayload, Instant occurredAt, String payloadHash
) { }
