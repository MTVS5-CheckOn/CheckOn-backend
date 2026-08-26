package com.checkon.problem.integration.kafka;

import java.time.Instant;
import java.util.UUID;

import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.domain.ProblemGenerationExecutionStatus;

public record ParsedProblemGenerationResultEvent(
	UUID eventId, String eventType, String schemaVersion, UUID requestId,
	UUID problemExecutionId, UUID revisionRequestId, Integer targetIndex, UUID adapterExecutionId,
	String tenantAlias, EventKind kind, ProblemGenerationStatus status,
	ProblemGenerationExecutionStatus executionStatus, String workerPhase, String domainStatus, String jobId,
	String aiExecutionId, String setId, String resultStatus, String errorCode,
	String conflictReason, Integer currentRevisionNo,
	Integer requestedCount, Integer processedCount, Integer unstartedCount, String statusCountsPayload,
	String resultPayload, String slotPayload, String versionsPayload, Instant occurredAt, String payloadHash
) {
	public enum EventKind { WORKER, SLOT_DETAIL, REVISION_RESULT }
}
