package com.checkon.counsel.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.CachedSuggestions;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.StoredSuggestion;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository.CurrentLabel;
import com.checkon.counsel.integration.ai.GuardianLabelClient;
import com.checkon.counsel.integration.ai.GuardianLabelClientException;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;

@Service
public class GuardianLabelSuggestionService {

	private final GuardianLabelSuggestionTransactions transactions;
	private final GuardianLabelClient client;

	public GuardianLabelSuggestionService(
		GuardianLabelSuggestionTransactions transactions,
		GuardianLabelClient client
	) {
		this.transactions = transactions;
		this.client = client;
	}

	public Result suggest(UUID teacherId, UUID parentId) {
		var prepared = transactions.prepare(teacherId, parentId);
		if (!prepared.eligible()) {
			var reason = prepared.history().size() < 5
				? EligibilityReason.INSUFFICIENT_HISTORY : EligibilityReason.ALL_LABELS_SET;
			return new Result(false, reason, false, prepared.guardianRef(), prepared.history().size(),
				prepared.currentLabels(), List.of());
		}
		if (prepared.cached().isPresent()) return result(prepared, prepared.cached().get(), true);

		try {
			var response = client.suggest(
				new GuardianLabelSuggestionRequest(prepared.guardianRef(), prepared.history()),
				prepared.tenantAlias(), UUID.randomUUID().toString()
			);
			return result(prepared, transactions.store(teacherId, prepared, response), false);
		}
		catch (GuardianLabelClientException exception) {
			if (exception.reason() == GuardianLabelClientException.Reason.INTERNAL_ERROR
				&& exception.responseBody() != null
				&& exception.responseBody().contains("history_all_blocked")) {
				return new Result(false, EligibilityReason.ANALYSIS_UNAVAILABLE, false, prepared.guardianRef(),
					prepared.history().size(), prepared.currentLabels(), List.of());
			}
			throw GuardianLabelSuggestionException.upstream(exception);
		}
	}

	private static Result result(
		GuardianLabelSuggestionTransactions.PreparedRequest prepared,
		CachedSuggestions cached,
		boolean cacheHit
	) {
		var setAxes = prepared.currentLabels().stream().map(CurrentLabel::axis).collect(java.util.stream.Collectors.toSet());
		return new Result(true, null, cacheHit, cached.guardianRef(), prepared.history().size(),
			prepared.currentLabels(), cached.suggestions().stream()
				.filter(suggestion -> !setAxes.contains(suggestion.axis()))
				.map(GuardianLabelSuggestionService::view).toList());
	}

	private static SuggestionView view(StoredSuggestion suggestion) {
		return new SuggestionView(
			suggestion.suggestionRef(), suggestion.suggestionId(), suggestion.axis(), suggestion.value(),
			suggestion.confidence(), suggestion.evidenceJson()
		);
	}

	public record Result(
		boolean eligible,
		EligibilityReason reason,
		boolean cacheHit,
		String guardianRef,
		int historyCount,
		List<CurrentLabel> currentLabels,
		List<SuggestionView> suggestions
	) {
	}

	public record SuggestionView(
		UUID suggestionRef,
		String suggestionId,
		GuardianLabelAxis axis,
		GuardianLabelValue value,
		java.math.BigDecimal confidence,
		String evidenceJson
	) {
	}

	public enum EligibilityReason {
		INSUFFICIENT_HISTORY,
		ALL_LABELS_SET,
		ANALYSIS_UNAVAILABLE
	}
}
