package com.checkon.counsel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.infrastructure.persistence.GuardianCommunicationHistoryRepository;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository.CurrentLabel;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest.Action;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.application.AiProblemAliasService;

@Service
public class GuardianLabelDecisionTransactions {

	private final GuardianCommunicationHistoryRepository relationships;
	private final GuardianLabelDecisionRepository decisions;
	private final AiProblemAliasService tenantAliases;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public GuardianLabelDecisionTransactions(
		GuardianCommunicationHistoryRepository relationships,
		GuardianLabelDecisionRepository decisions,
		AiProblemAliasService tenantAliases,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.relationships = relationships;
		this.decisions = decisions;
		this.tenantAliases = tenantAliases;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CurrentLabel> currentLabels(UUID teacherId, UUID parentId) {
		requireTarget(teacherId, parentId);
		return decisions.findCurrentLabels(teacherId, parentId);
	}

	@Transactional
	public StoredDecision decide(
		UUID teacherId, UUID parentId, UUID suggestionRef,
		Action action, GuardianLabelValue correctedValue
	) {
		requireTarget(teacherId, parentId);
		if (suggestionRef == null || action == null) throw GuardianLabelSuggestionException.invalidDecision();
		var suggestion = decisions.findSuggestion(teacherId, parentId, suggestionRef)
			.orElseThrow(GuardianLabelSuggestionException::suggestionNotFound);
		GuardianLabelValue decidedValue = validate(action, correctedValue, suggestion.axis(), suggestion.value());
		Instant now = Instant.now(clock);
		boolean inserted = decisions.insertDecision(
			UUID.randomUUID(), suggestion, teacherId, parentId, action, decidedValue, now
		);
		if (!inserted) {
			var stored = decisions.findDecision(suggestionRef)
				.orElseThrow(() -> new IllegalStateException("guardian label decision conflict could not be resolved"));
			if (stored.action() != action || stored.value() != decidedValue) {
				throw GuardianLabelSuggestionException.decisionConflict();
			}
		}
		else if (action != Action.rejected) {
			decisions.upsertCurrentLabel(
				teacherId, parentId, suggestion.axis(), decidedValue, suggestion.suggestionId(), now
			);
		}
		return new StoredDecision(
			inserted, tenantAliases.getOrCreateTenantAlias(teacherId), suggestion.suggestionId(),
			action, suggestion.axis(), decidedValue
		);
	}

	private void requireTarget(UUID teacherId, UUID parentId) {
		if (teacherId == null) throw GuardianLabelSuggestionException.invalidPrincipal();
		if (parentId == null) throw GuardianLabelSuggestionException.targetNotFound();
		tenantContext.setCurrentTeacher(teacherId);
		if (!relationships.hasActiveTeacherRelationship(teacherId, parentId)) {
			throw GuardianLabelSuggestionException.targetNotFound();
		}
	}

	private static GuardianLabelValue validate(
		Action action, GuardianLabelValue correctedValue,
		GuardianLabelAxis axis, GuardianLabelValue suggestedValue
	) {
		return switch (action) {
			case confirmed -> {
				if (correctedValue != null) throw GuardianLabelSuggestionException.invalidDecision();
				yield suggestedValue;
			}
			case corrected -> {
				if (correctedValue == null || correctedValue.axis() != axis) {
					throw GuardianLabelSuggestionException.invalidDecision();
				}
				yield correctedValue;
			}
			case rejected -> {
				if (correctedValue != null) throw GuardianLabelSuggestionException.invalidDecision();
				yield null;
			}
		};
	}

	public record StoredDecision(
		boolean newlyCreated,
		String tenantAlias,
		String suggestionId,
		Action action,
		GuardianLabelAxis axis,
		GuardianLabelValue value
	) {
	}
}
