package com.checkon.problem.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.checkon.problem.domain.ProblemValidationStatus;
import com.checkon.problem.infrastructure.persistence.ProblemStudioWorkflowRepository;
import com.checkon.problem.infrastructure.persistence.ProblemStudioWorkflowRepository.NewItem;
import com.checkon.problem.infrastructure.persistence.ProblemStudioWorkflowRepository.NewSlot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemGenerationItemProjector {
	private final ProblemStudioWorkflowRepository workflow;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ProblemGenerationItemProjector(
		ProblemStudioWorkflowRepository workflow,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.workflow = workflow;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public void project(UUID teacherId, UUID requestId, String resultPayload) {
		project(teacherId,requestId,null,resultPayload);
	}

	public void project(UUID teacherId, UUID requestId, UUID executionId,String resultPayload) {
		JsonNode result = read(resultPayload);
		JsonNode items = locateItems(result);
		if (items == null || !items.isArray() || items.isEmpty()) {
			workflow.markProjection(teacherId, requestId, "UNSUPPORTED", "RESULT_ITEMS_MISSING");
			return;
		}

		int projected = 0;
		int ordinalBase = workflow.maxItemOrdinal(teacherId, requestId);
		int unsupported = 0;
		Set<String> externalIds = new HashSet<>();
		Instant now = Instant.now(clock);
		for (int index = 0; index < items.size(); index++) {
			JsonNode wrapper=items.get(index);
			JsonNode item=executionId==null?wrapper:wrapper.get("item");
			int slotIndex=executionId==null?index:intValue(wrapper,"slot_index",index);
			String wrapperStatus=executionId==null?null:firstText(wrapper,"status");
			if(executionId!=null && (item==null||!item.isObject())) {
				workflow.insertSlot(new NewSlot(teacherId,requestId,executionId,slotIndex,null,
					firstText(wrapper,"item_id"),ProblemValidationStatus.EXCLUDED,intValue(wrapper,"current_revision_no",0),
					firstText(wrapper,"review_reason"),firstText(wrapper,"failure_reason"),
					firstValue(wrapper,"failure_detail"),write(wrapper),now));
				continue;
			}
			String stem = firstText(item, "stem", "question", "prompt", "question_text");
			List<String> options = options(item);
			if (isBlank(stem) || options.size() < 2) {
				unsupported++;
				continue;
			}
			String externalId = firstText(item, "id", "problem_id", "question_id", "item_id");
			if (externalId != null && !externalIds.add(externalId)) {
				externalId = null;
				unsupported++;
			}
			String correctAnswer = correctAnswer(item, options);
			Validation validation = validation(item, correctAnswer,wrapperStatus,firstText(wrapper,"review_reason"));
			UUID itemId = workflow.insertItem(new NewItem(
				teacherId, requestId, externalId, ordinalBase + index + 1, stem,
				firstText(item, "passage", "context"), correctAnswer,
				firstText(item, "explanation", "rationale", "solution"),
				firstValue(item, "source_basis", "generation_basis", "basis", "evidence"),
				validation.status(), validation.message(), write(item), now
			));
			for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
				workflow.insertOption(teacherId, requestId, itemId, optionIndex + 1, options.get(optionIndex));
			}
			if(executionId!=null) workflow.insertSlot(new NewSlot(teacherId,requestId,executionId,slotIndex,itemId,
				firstText(wrapper,"item_id"),validation.status(),intValue(wrapper,"current_revision_no",0),
				firstText(wrapper,"review_reason"),firstText(wrapper,"failure_reason"),firstValue(wrapper,"failure_detail"),
				write(wrapper),now));
			projected++;
		}
		if(executionId!=null) {
			int requested=intValue(result,"requested_count",items.size()); int processed=intValue(result,"processed_count",items.size());
			JsonNode counts=result.get("status_counts"); workflow.updateExecutionSummary(teacherId,executionId,requested,processed,
				counts==null?"{}":write(counts));
		}
		if (projected == 0) {
			workflow.markProjection(teacherId, requestId, "UNSUPPORTED", "ITEM_SCHEMA_UNSUPPORTED");
		}
		else if (unsupported > 0) {
			workflow.markProjection(teacherId, requestId, "PARTIAL", "ITEM_SCHEMA_PARTIAL");
		}
		else {
			workflow.markProjection(teacherId, requestId, "PROJECTED", null);
		}
	}

	public boolean hasProjectedItems(UUID teacherId, UUID requestId) {
		return workflow.countItems(teacherId, requestId) > 0;
	}

	private JsonNode locateItems(JsonNode root) {
		if (root == null || root.isNull()) return null;
		for (String field : List.of("problems", "items", "questions")) {
			JsonNode candidate = root.get(field);
			if (candidate != null && candidate.isArray()) return candidate;
		}
		JsonNode data = root.get("data");
		if (data != null && data.isObject()) return locateItems(data);
		return null;
	}

	private List<String> options(JsonNode item) {
		JsonNode array = firstNode(item, "options", "choices", "answers");
		if (array == null || !array.isArray()) return List.of();
		List<String> result = new ArrayList<>();
		for (JsonNode option : array) {
			String value = option.isTextual()
				? option.asText()
				: firstText(option, "text", "content", "value", "label");
			if (!isBlank(value)) result.add(value.trim());
		}
		return List.copyOf(result);
	}

	private String correctAnswer(JsonNode item, List<String> options) {
		JsonNode answer = firstNode(item, "correct_answer", "answer", "correctAnswer");
		if (answer != null && !answer.isNull()) {
			if (answer.isObject()) {
				JsonNode correctNo = answer.get("correct_no");
				if (correctNo != null && correctNo.canConvertToInt()) {
					int oneBased = correctNo.asInt();
					return oneBased >= 1 && oneBased <= options.size() ? options.get(oneBased - 1) : null;
				}
				return null;
			}
			String raw = answer.asText().trim();
			String matched = matchOption(raw, options);
			return matched == null ? raw : matched;
		}
		JsonNode index = firstNode(item, "correct_option_index", "answer_index");
		if (index != null && index.canConvertToInt()) {
			int value = index.asInt();
			if (value >= 0 && value < options.size()) return options.get(value);
			if (value >= 1 && value <= options.size()) return options.get(value - 1);
		}
		return null;
	}

	private static String matchOption(String raw, List<String> options) {
		for (String option : options) if (option.equals(raw)) return option;
		if (raw.length() == 1 && Character.isLetter(raw.charAt(0))) {
			int index = Character.toUpperCase(raw.charAt(0)) - 'A';
			if (index >= 0 && index < options.size()) return options.get(index);
		}
		try {
			int index = Integer.parseInt(raw);
			if (index >= 1 && index <= options.size()) return options.get(index - 1);
		}
		catch (NumberFormatException ignored) {
			// The AI may return the exact option text instead of a numeric label.
		}
		return null;
	}

	private Validation validation(JsonNode item, String correctAnswer,String statusOverride,String messageOverride) {
		String raw = statusOverride==null?firstText(item, "validation_status", "verification_status"):statusOverride;
		JsonNode verification = item.get("verification");
		if (raw == null && verification != null) raw = firstText(verification, "status", "result");
		String message = messageOverride==null?firstText(item, "validation_message", "verification_message", "exclusion_reason"):messageOverride;
		if (message == null && verification != null) message = firstText(verification, "message", "reason");
		ProblemValidationStatus status = toValidationStatus(raw);
		if (correctAnswer == null) {
			return new Validation(ProblemValidationStatus.UNVERIFIABLE,
				message == null ? "AI_RESULT_ANSWER_MISSING" : message);
		}
		if (status == null) {
			return new Validation(ProblemValidationStatus.UNVERIFIABLE,
				message == null ? "AI_VALIDATION_STATUS_MISSING" : message);
		}
		return new Validation(status, message);
	}
	private static int intValue(JsonNode node,String field,int fallback) { JsonNode value=node==null?null:node.get(field);
		return value!=null&&value.canConvertToInt()&&value.asInt()>=0?value.asInt():fallback; }

	private static ProblemValidationStatus toValidationStatus(String raw) {
		if (isBlank(raw)) return null;
		return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
			case "passed", "pass", "verified", "valid", "success" -> ProblemValidationStatus.PASSED;
			case "review_required", "needs_review", "warning", "manual_review" -> ProblemValidationStatus.REVIEW_REQUIRED;
			case "unverifiable", "verification_unavailable", "verification_failed", "invalid", "error" -> ProblemValidationStatus.UNVERIFIABLE;
			case "excluded", "dropped", "discarded", "rejected", "disposed" -> ProblemValidationStatus.EXCLUDED;
			default -> null;
		};
	}

	private static JsonNode firstNode(JsonNode node, String... names) {
		if (node == null || !node.isObject()) return null;
		for (String name : names) {
			JsonNode value = node.get(name);
			if (value != null && !value.isNull()) return value;
		}
		return null;
	}

	private static String firstText(JsonNode node, String... names) {
		JsonNode value = firstNode(node, names);
		if (value == null || !value.isValueNode()) return null;
		String text = value.asText();
		return isBlank(text) ? null : text.trim();
	}

	private static String firstValue(JsonNode node, String... names) {
		JsonNode value = firstNode(node, names);
		if (value == null) return null;
		if (value.isValueNode()) {
			String text = value.asText();
			return isBlank(text) ? null : text.trim();
		}
		return value.toString();
	}

	private JsonNode read(String value) {
		if (value == null) return null;
		try {
			return objectMapper.readTree(value);
		}
		catch (JacksonException exception) {
			return null;
		}
	}

	private String write(JsonNode value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("problem item JSON serialization failed", exception);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record Validation(ProblemValidationStatus status, String message) { }
}
