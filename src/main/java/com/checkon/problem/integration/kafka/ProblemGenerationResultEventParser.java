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
	private final ObjectMapper objectMapper;
	private final ProblemGenerationPayloadHasher hasher;
	public ProblemGenerationResultEventParser(ObjectMapper objectMapper, ProblemGenerationPayloadHasher hasher) {
		this.objectMapper = objectMapper; this.hasher = hasher;
	}

	public ParsedProblemGenerationResultEvent parse(String rawPayload) {
		if (rawPayload == null || rawPayload.isBlank()) throw contract("event payload must not be blank");
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

			validateWorkerKind(eventType, payload);
			ProblemGenerationStatus status = status(eventType, payload);
			UUID problemExecutionId = optionalUuid(payload.get("problem_execution_id"), "payload.problem_execution_id");
			Integer targetIndex = optionalNonNegativeInt(payload.get("target_index"), "payload.target_index");
			UUID adapterExecutionId = optionalUuid(payload.get("adapter_execution_id"), "payload.adapter_execution_id");
			ProblemGenerationExecutionStatus executionStatus = executionStatus(eventType, payload, status);
			JsonNode result = object(payload, "result");
			JsonNode meta = object(root, "meta");
			String jobId = firstText(payload, "job_id");
			String executionId = firstNonBlank(firstText(payload, "execution_id"), firstText(meta, "execution_id"));
			String setId = firstNonBlank(firstText(payload, "set_id"), firstText(result, "set_id"));
			String resultStatus = firstNonBlank(firstText(payload, "result_status"), firstText(result, "status"), firstText(result, "outcome"));
			String errorCode = firstNonBlank(firstText(payload, "error_code"), firstText(object(payload, "error"), "code"));
			JsonNode versions = object(payload, "versions");
			if (versions == null) versions = object(meta, "versions");
			String resultPayload = status == ProblemGenerationStatus.RUNNING ? null : writeJson(result == null ? payload : result);
			String versionsPayload = versions == null ? null : writeJson(versions);
			return new ParsedProblemGenerationResultEvent(eventId, eventType, schemaVersion, requestId,
				problemExecutionId, targetIndex, adapterExecutionId, tenantAlias, status, executionStatus, jobId, executionId, setId, resultStatus, errorCode,
				resultPayload, versionsPayload, occurredAt(requiredText(root, "occurred_at")), hasher.sha256(rawPayload));
		}
		catch (ProblemGenerationEventContractException exception) { throw exception; }
		catch (JacksonException exception) { throw new ProblemGenerationEventContractException("event payload is not valid JSON", exception); }
	}
	private static ProblemGenerationExecutionStatus executionStatus(String eventType, JsonNode payload, ProblemGenerationStatus parentStatus) {
		String value = firstNonBlank(firstText(payload,"child_status"), firstText(payload,"phase"), firstText(payload,"result_status"));
		if (value != null) {
			String normalized = value.toLowerCase(Locale.ROOT).replace('-','_');
			if (normalized.equals("timed_out") || normalized.equals("timeout")) return ProblemGenerationExecutionStatus.TIMED_OUT;
			if (normalized.equals("delivery_failed")) return ProblemGenerationExecutionStatus.DELIVERY_FAILED;
			if (normalized.equals("rejected_insufficient")) return ProblemGenerationExecutionStatus.REJECTED_INSUFFICIENT;
			if (normalized.equals("cancelled")) return ProblemGenerationExecutionStatus.CANCELLED;
		}
		return switch (parentStatus) {
			case RUNNING -> ProblemGenerationExecutionStatus.RUNNING;
			case SUCCEEDED, PARTIAL_SUCCESS -> ProblemGenerationExecutionStatus.SUCCEEDED;
			case FAILED, DELIVERY_FAILED -> ProblemGenerationExecutionStatus.FAILED;
			case CANCELLED -> ProblemGenerationExecutionStatus.CANCELLED;
			case QUEUED, DISPATCHED -> throw contract("result event cannot move child to a request-only phase");
		};
	}

	private static ProblemGenerationStatus status(String eventType, JsonNode payload) {
		String normalized = eventType.toLowerCase(Locale.ROOT);
		if (!(normalized.startsWith("worker_job.") || normalized.startsWith("problem_generation."))) throw contract("unsupported event_type");
		if (normalized.endsWith(".succeeded")) return ProblemGenerationStatus.SUCCEEDED;
		if (normalized.endsWith(".failed")) return ProblemGenerationStatus.FAILED;
		if (normalized.endsWith(".cancelled")) return ProblemGenerationStatus.CANCELLED;
		if (normalized.endsWith(".progress") || normalized.endsWith(".running")) return ProblemGenerationStatus.RUNNING;
		if ("running".equalsIgnoreCase(firstText(payload, "phase"))) return ProblemGenerationStatus.RUNNING;
		throw contract("event_type does not describe a supported phase");
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
