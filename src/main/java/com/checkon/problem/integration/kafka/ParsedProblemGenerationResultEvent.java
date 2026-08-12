package com.checkon.problem.integration.kafka;

import java.time.Instant;
import java.util.UUID;

import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.domain.ProblemGenerationExecutionStatus;

public record ParsedProblemGenerationResultEvent(
	UUID eventId, String eventType, String schemaVersion, UUID requestId,
	UUID problemExecutionId, Integer targetIndex, UUID adapterExecutionId,
	String tenantAlias, ProblemGenerationStatus status, ProblemGenerationExecutionStatus executionStatus, String jobId,
	String aiExecutionId, String setId, String resultStatus, String errorCode,
	String resultPayload, String versionsPayload, Instant occurredAt, String payloadHash
) { }
