package com.checkon.problem.integration.kafka;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.checkon.problem.application.ProblemGenerationPayloadHasher;
import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.domain.ProblemGenerationExecutionStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemGenerationResultEventParser {
	private static final Pattern TENANT_ALIAS = Pattern.compile("tn_[0-9a-f]{32}");
	private static final int REFERENCE_EVENT_MAX_BYTES = 64 * 1024;
	private static final int DETAIL_EVENT_MAX_BYTES = 1024 * 1024;
	private final ObjectMapper objectMapper;
	private final ProblemGenerationPayloadHasher hasher;
	public ProblemGenerationResultEventParser(ObjectMapper objectMapper, ProblemGenerationPayloadHasher hasher) {
		this.objectMapper = objectMapper; this.hasher = hasher;
	}

	public ParsedProblemGenerationResultEvent parse(String rawPayload) {
		if (rawPayload == null || rawPayload.isBlank()) throw contract("event payload must not be blank");
		int payloadBytes = rawPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		if (payloadBytes > DETAIL_EVENT_MAX_BYTES) throw contract("event payload exceeds the 1 MiB detail limit");
		try {
			JsonNode root = objectMapper.readTree(rawPayload);
			JsonNode payload = requiredObject(root, "payload");
			UUID eventId = uuid(requiredText(root, "event_id"), "event_id");
			String eventType = requiredText(root, "event_type");
			String schemaVersion = requiredText(root, "schema_version");
			String tenantAlias = requiredText(root, "tenant_id");
			if (!TENANT_ALIAS.matcher(tenantAlias).matches()) throw contract("tenant_id must be an opaque tn_ alias");
			UUID correlationId = optionalUuid(root.get("correlation_id"), "correlation_id");
			UUID payloadRequestId = optionalUuid(payload.get("problem_request_id"), "payload.problem_request_id");
			if (correlationId != null && payloadRequestId != null && !correlationId.equals(payloadRequestId))
				throw contract("correlation_id and problem_request_id must match");
			UUID requestId = correlationId != null ? correlationId : payloadRequestId;
			if (requestId == null) throw contract("problem_request_id or correlation_id is required");

			ParsedProblemGenerationResultEvent.EventKind kind = eventKind(eventType);
			if (kind == ParsedProblemGenerationResultEvent.EventKind.WORKER
				&& payloadBytes > REFERENCE_EVENT_MAX_BYTES)
				throw contract("worker reference payload exceeds the 64 KiB limit");
			validateWorkerKind(eventType, payload);
			String workerPhase = workerPhase(eventType, payload, kind);
			ProblemGenerationStatus status = status(workerPhase, kind);
			UUID problemExecutionId = optionalUuid(payload.get("problem_execution_id"), "payload.problem_execution_id");
			UUID revisionRequestId = optionalUuid(payload.get("revision_request_id"), "payload.revision_request_id");
			Integer targetIndex = optionalNonNegativeInt(payload.get("target_index"), "payload.target_index");
			UUID adapterExecutionId = optionalUuid(payload.get("adapter_execution_id"), "payload.adapter_execution_id");
			String domainStatus = normalizeDomainStatus(firstNonBlank(
				firstText(payload, "domain_status"), firstText(payload, "result_status")));
			ProblemGenerationExecutionStatus executionStatus = executionStatus(workerPhase, domainStatus, kind);
			JsonNode result = object(payload, "result");
			JsonNode slot = kind != ParsedProblemGenerationResultEvent.EventKind.WORKER
				? firstObject(payload, "slot", "detail") : null;
			if (kind == ParsedProblemGenerationResultEvent.EventKind.SLOT_DETAIL && slot == null)
				throw contract("slot detail event requires payload.slot");
			JsonNode meta = object(root, "meta");
			String jobId = firstText(payload, "job_id");
			String executionId = firstNonBlank(firstText(payload, "execution_id"), firstText(meta, "execution_id"));
			String setId = firstNonBlank(firstText(payload, "set_id"), firstText(result, "set_id"));
			String resultStatus = firstNonBlank(firstText(payload, "result_status"), firstText(result, "status"), firstText(result, "outcome"));
			String errorCode = firstNonBlank(firstText(payload, "error_code"), firstText(object(payload, "error"), "code"));
			JsonNode errorDetail=object(object(payload,"error"),"detail");
			String conflictReason=firstNonBlank(firstText(payload,"conflict_reason"),firstText(errorDetail,"reason"));
			Integer currentRevisionNo=optionalNonNegativeInt(firstNode(payload,errorDetail,"current_revision_no"),"current_revision_no");
			JsonNode versions = object(payload, "versions");
			if (versions == null) versions = object(meta, "versions");
			if (domainStatus == null) domainStatus = normalizeDomainStatus(
				firstNonBlank(firstText(result, "status"), firstText(result, "outcome")));
			Integer requestedCount = optionalNonNegativeInt(firstNode(payload, result, "requested_count"), "requested_count");
			Integer processedCount = optionalNonNegativeInt(firstNode(payload, result, "processed_count"), "processed_count");
			Integer unstartedCount = optionalNonNegativeInt(firstNode(payload, result, "unstarted_count"), "unstarted_count");
			JsonNode statusCounts = firstNode(payload, result, "status_counts");
			String resultPayload = kind == ParsedProblemGenerationResultEvent.EventKind.WORKER
				&& status != ProblemGenerationStatus.RUNNING ? writeJson(result == null ? payload : result) : null;
			String slotPayload = slot == null ? null : writeJson(slot);
			String versionsPayload = versions == null ? null : writeJson(versions);
			return new ParsedProblemGenerationResultEvent(eventId, eventType, schemaVersion, requestId,
				problemExecutionId, revisionRequestId, targetIndex, adapterExecutionId, tenantAlias, kind, status, executionStatus,
				workerPhase, domainStatus, jobId, executionId, setId, resultStatus, errorCode,
				conflictReason,currentRevisionNo,
				requestedCount, processedCount, unstartedCount, statusCounts == null ? null : writeJson(statusCounts),
				resultPayload, slotPayload, versionsPayload, occurredAt(requiredText(root, "occurred_at")), hasher.sha256(rawPayload));
		}
		catch (ProblemGenerationEventContractException exception) { throw exception; }
		catch (JacksonException exception) { throw new ProblemGenerationEventContractException("event payload is not valid JSON", exception); }
	}
	private static ProblemGenerationExecutionStatus executionStatus(String workerPhase, String domainStatus,
		ParsedProblemGenerationResultEvent.EventKind kind) {
		if (kind != ParsedProblemGenerationResultEvent.EventKind.WORKER) return ProblemGenerationExecutionStatus.RUNNING;
		if ("cancelled".equals(workerPhase)) return ProblemGenerationExecutionStatus.CANCELLED;
		if ("failed".equals(workerPhase)) return ProblemGenerationExecutionStatus.FAILED;
		if ("succeeded".equals(workerPhase) && "rejected_insufficient".equals(domainStatus))
			return ProblemGenerationExecutionStatus.REJECTED_INSUFFICIENT;
		return "succeeded".equals(workerPhase)
			? ProblemGenerationExecutionStatus.SUCCEEDED : ProblemGenerationExecutionStatus.RUNNING;
	}

	private static ProblemGenerationStatus status(String workerPhase,
		ParsedProblemGenerationResultEvent.EventKind kind) {
		if (kind != ParsedProblemGenerationResultEvent.EventKind.WORKER) return ProblemGenerationStatus.RUNNING;
		return switch (workerPhase) {
			case "succeeded" -> ProblemGenerationStatus.SUCCEEDED;
			case "failed" -> ProblemGenerationStatus.FAILED;
			case "cancelled" -> ProblemGenerationStatus.CANCELLED;
			default -> ProblemGenerationStatus.RUNNING;
		};
	}

	private static ParsedProblemGenerationResultEvent.EventKind eventKind(String eventType) {
		String normalized = eventType.toLowerCase(Locale.ROOT);
		if (normalized.equals("problem_generation.slot.detail"))
			return ParsedProblemGenerationResultEvent.EventKind.SLOT_DETAIL;
		if (normalized.startsWith("problem_generation.revision."))
			return ParsedProblemGenerationResultEvent.EventKind.REVISION_RESULT;
		if (normalized.startsWith("worker_job.") || normalized.startsWith("problem_generation."))
			return ParsedProblemGenerationResultEvent.EventKind.WORKER;
		throw contract("unsupported event_type");
	}

	private static String workerPhase(String eventType, JsonNode payload,
		ParsedProblemGenerationResultEvent.EventKind kind) {
		if (kind != ParsedProblemGenerationResultEvent.EventKind.WORKER) return "running";
		String value = firstNonBlank(firstText(payload, "worker_phase"), firstText(payload, "phase"));
		if (value == null) {
			String normalized = eventType.toLowerCase(Locale.ROOT);
			for (String candidate : java.util.List.of("queued", "leased", "running", "paused", "succeeded", "failed", "cancelled"))
				if (normalized.endsWith("." + candidate)) return candidate;
			if (normalized.endsWith(".progress")) return "running";
			throw contract("worker event does not describe a supported phase");
		}
		String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_');
		if (!java.util.Set.of("queued", "leased", "running", "paused", "succeeded", "failed", "cancelled").contains(normalized))
			throw contract("worker_phase is not supported");
		return normalized;
	}
	private static String normalizeDomainStatus(String value) {
		if (value == null) return null;
		return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
			case "generated", "completed", "success", "succeeded" -> "generated";
			case "partial", "partial_success" -> "partial_success";
			case "failed" -> "failed";
			case "rejected_insufficient", "insufficient" -> "rejected_insufficient";
			default -> null;
		};
	}
	private static void validateWorkerKind(String eventType, JsonNode payload) {
		if (!eventType.startsWith("worker_job.")) return;
		String kind = firstText(payload, "worker_kind");
		if (kind != null && !kind.equals("problem_generation") && !kind.equals("problem-generation") && !kind.equals("M2"))
			throw contract("worker_job event is not for problem generation");
	}
	private static JsonNode requiredObject(JsonNode node, String field) {
		JsonNode value = object(node, field); if (value == null) throw contract(field + " must be an object"); return value;
	}
	private static JsonNode object(JsonNode node, String field) {
		if (node == null) return null; JsonNode value = node.get(field); return value != null && value.isObject() ? value : null;
	}
	private static JsonNode firstObject(JsonNode node, String... fields) {
		for (String field : fields) { JsonNode value = object(node, field); if (value != null) return value; }
		return null;
	}
	private static JsonNode firstNode(JsonNode primary, JsonNode secondary, String field) {
		JsonNode value = primary == null ? null : primary.get(field);
		if (value == null || value.isNull()) value = secondary == null ? null : secondary.get(field);
		return value == null || value.isNull() ? null : value;
	}
	private static String requiredText(JsonNode node, String field) {
		String value = firstText(node, field); if (value == null) throw contract(field + " must not be blank"); return value;
	}
	private static String firstText(JsonNode node, String field) {
		if (node == null) return null; JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.isTextual()) return null;
		String text = value.asText().trim(); return text.isEmpty() ? null : text;
	}
	private static String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value; return null;
	}
	private static UUID optionalUuid(JsonNode value, String field) {
		if (value == null || value.isNull()) return null;
		if (!value.isTextual()) throw contract(field + " must be a UUID string"); return uuid(value.asText(), field);
	}
	private static Integer optionalNonNegativeInt(JsonNode value, String field) {
		if (value == null || value.isNull()) return null;
		if (!value.canConvertToInt() || value.asInt() < 0) throw contract(field + " must be a non-negative integer");
		return value.asInt();
	}
	private static UUID uuid(String value, String field) {
		try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw contract(field + " must be a UUID"); }
	}
	private static java.time.Instant occurredAt(String value) {
		try { return OffsetDateTime.parse(value).toInstant(); }
		catch (DateTimeParseException exception) { throw contract("occurred_at must be ISO-8601 with an offset"); }
	}
	private String writeJson(JsonNode value) {
		try { return objectMapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new ProblemGenerationEventContractException("event JSON could not be normalized", exception); }
	}
	private static ProblemGenerationEventContractException contract(String message) { return new ProblemGenerationEventContractException(message); }
}
