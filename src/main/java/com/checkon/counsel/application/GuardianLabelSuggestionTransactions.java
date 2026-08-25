package com.checkon.counsel.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.infrastructure.persistence.GuardianCommunicationHistoryRepository;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository.CurrentLabel;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.CachedSuggestions;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.NewRequest;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.NewSuggestion;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionResponse;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.application.AiProblemAliasService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class GuardianLabelSuggestionTransactions {

	private static final int MAX_HISTORY = 10;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final GuardianCommunicationHistoryRepository historyRepository;
	private final GuardianLabelDecisionRepository decisionRepository;
	private final GuardianLabelSuggestionRepository suggestionRepository;
	private final AiParentLabelAliasService aliases;
	private final AiProblemAliasService tenantAliases;
	private final InquiryTextMaskingService masking;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public GuardianLabelSuggestionTransactions(
		GuardianCommunicationHistoryRepository historyRepository,
		GuardianLabelDecisionRepository decisionRepository,
		GuardianLabelSuggestionRepository suggestionRepository,
		AiParentLabelAliasService aliases,
		AiProblemAliasService tenantAliases,
		InquiryTextMaskingService masking,
		TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.historyRepository = historyRepository;
		this.decisionRepository = decisionRepository;
		this.suggestionRepository = suggestionRepository;
		this.aliases = aliases;
		this.tenantAliases = tenantAliases;
		this.masking = masking;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public PreparedRequest prepare(UUID teacherId, UUID parentId) {
		if (teacherId == null) throw GuardianLabelSuggestionException.invalidPrincipal();
		if (parentId == null) throw GuardianLabelSuggestionException.targetNotFound();
		tenantContext.setCurrentTeacher(teacherId);
		if (!historyRepository.hasActiveTeacherRelationship(teacherId, parentId)) {
			throw GuardianLabelSuggestionException.targetNotFound();
		}

		String guardianRef = aliases.getOrCreate(teacherId, parentId);
		String tenantAlias = tenantAliases.getOrCreateTenantAlias(teacherId);
		List<String> names = historyRepository.findLinkedStudentRealNames(teacherId, parentId);
		List<GuardianLabelSuggestionRequest.HistoryRecord> history = historyRepository
			.findLatest(teacherId, parentId, MAX_HISTORY).stream()
			.map(entry -> new GuardianLabelSuggestionRequest.HistoryRecord(
				entry.recordId(), entry.direction(), masking.mask(entry.text(), names), entry.at().atZone(SEOUL).toOffsetDateTime()
			)).toList();
		List<CurrentLabel> currentLabels = decisionRepository.findCurrentLabels(teacherId, parentId);
		if (history.size() < 5 || currentLabels.size() == 4) {
			return new PreparedRequest(parentId, guardianRef, tenantAlias, history, currentLabels, Optional.empty());
		}

		String latestRecordId = history.getLast().recordId();
		return new PreparedRequest(
			parentId, guardianRef, tenantAlias, history, currentLabels,
			suggestionRepository.findCached(teacherId, parentId, history.size(), latestRecordId)
		);
	}

	@Transactional
	public CachedSuggestions store(
		UUID teacherId,
		PreparedRequest prepared,
		GuardianLabelSuggestionResponse response
	) {
		tenantContext.setCurrentTeacher(teacherId);
		Instant now = Instant.now(clock);
		String latestRecordId = prepared.history().getLast().recordId();
		UUID requestId = suggestionRepository.insertRequestIfAbsent(new NewRequest(
			UUID.randomUUID(), teacherId, prepared.parentId(), prepared.guardianRef(), prepared.history().size(),
			latestRecordId, writeJson(prepared.history()),
			response.meta() == null ? null : response.meta().executionId(),
			response.meta() == null ? null : writeJson(response.meta().versions()), now
		));
		for (var suggestion : response.data().suggestions()) {
			suggestionRepository.insertSuggestion(new NewSuggestion(
				UUID.randomUUID(), requestId, teacherId, prepared.parentId(), suggestion.suggestionId(),
				suggestion.label().axis(), suggestion.label().value(), suggestion.confidence(),
				writeJson(suggestion.evidenceQuotes()), now
			));
		}
		return suggestionRepository.findCached(
			teacherId, prepared.parentId(), prepared.history().size(), latestRecordId
		).orElseThrow(() -> new IllegalStateException("stored guardian label suggestions could not be loaded"));
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("guardian label data could not be serialized", exception);
		}
	}

	public record PreparedRequest(
		UUID parentId,
		String guardianRef,
		String tenantAlias,
		List<GuardianLabelSuggestionRequest.HistoryRecord> history,
		List<CurrentLabel> currentLabels,
		Optional<CachedSuggestions> cached
	) {
		public PreparedRequest(
			UUID parentId, String guardianRef, String tenantAlias,
			List<GuardianLabelSuggestionRequest.HistoryRecord> history,
			Optional<CachedSuggestions> cached
		) {
			this(parentId, guardianRef, tenantAlias, history, List.of(), cached);
		}

		public boolean eligible() {
			return history.size() >= 5 && currentLabels.size() < 4;
		}
	}
}
