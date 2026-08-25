package com.checkon.counsel.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.application.GuardianLabelDecisionService;
import com.checkon.counsel.application.GuardianLabelSuggestionService;
import com.checkon.counsel.application.GuardianLabelSuggestionService.EligibilityReason;
import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest.Action;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianLabelController {

	private final GuardianLabelSuggestionService suggestions;
	private final GuardianLabelDecisionService decisions;
	private final ObjectMapper objectMapper;

	public GuardianLabelController(
		GuardianLabelSuggestionService suggestions,
		GuardianLabelDecisionService decisions,
		ObjectMapper objectMapper
	) {
		this.suggestions = suggestions;
		this.decisions = decisions;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/{parentId}/label-suggestions")
	public SuggestionResponse suggest(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID parentId
	) {
		var result = suggestions.suggest(teacherProfileId(principal), parentId);
		return new SuggestionResponse(
			result.eligible(), result.reason(), result.cacheHit(), result.historyCount(),
			result.currentLabels().stream().map(label -> new CurrentLabelResponse(
				label.axis(), label.value(), label.updatedAt()
			)).toList(),
			result.suggestions().stream().map(suggestion -> new SuggestionItem(
				suggestion.suggestionRef(), suggestion.suggestionId(),
				new LabelResponse(suggestion.axis(), suggestion.value()), suggestion.confidence(),
				readJson(suggestion.evidenceJson())
			)).toList()
		);
	}

	@GetMapping("/{parentId}/labels")
	public LabelsResponse currentLabels(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID parentId
	) {
		return new LabelsResponse(decisions.currentLabels(teacherProfileId(principal), parentId).stream()
			.map(label -> new CurrentLabelResponse(label.axis(), label.value(), label.updatedAt()))
			.toList());
	}

	@PostMapping("/{parentId}/label-decisions")
	public DecisionResponse decide(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID parentId,
		@Valid @RequestBody DecisionRequest request
	) {
		var result = decisions.decide(
			teacherProfileId(principal), parentId, request.suggestionRef(), request.action(), request.correctedValue()
		);
		return new DecisionResponse(request.suggestionRef(), result.action(), result.axis(), result.value());
	}

	private JsonNode readJson(String json) {
		try {
			return objectMapper.readTree(json);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("stored guardian label evidence is invalid", exception);
		}
	}

	private static UUID teacherProfileId(AuthenticatedAccount principal) {
		return principal == null ? null : principal.teacherProfileId();
	}

	public record SuggestionResponse(
		boolean eligible,
		EligibilityReason reason,
		boolean cacheHit,
		int historyCount,
		List<CurrentLabelResponse> currentLabels,
		List<SuggestionItem> suggestions
	) {
	}

	public record SuggestionItem(
		UUID suggestionRef,
		String suggestionId,
		LabelResponse label,
		BigDecimal confidence,
		JsonNode evidenceQuotes
	) {
	}

	public record LabelResponse(GuardianLabelAxis axis, GuardianLabelValue value) {
	}

	public record CurrentLabelResponse(GuardianLabelAxis axis, GuardianLabelValue value, Instant updatedAt) {
	}

	public record LabelsResponse(List<CurrentLabelResponse> labels) {
	}

	public record DecisionRequest(
		@NotNull UUID suggestionRef,
		@NotNull Action action,
		GuardianLabelValue correctedValue
	) {
	}

	public record DecisionResponse(
		UUID suggestionRef,
		Action action,
		GuardianLabelAxis axis,
		GuardianLabelValue value
	) {
	}
}
