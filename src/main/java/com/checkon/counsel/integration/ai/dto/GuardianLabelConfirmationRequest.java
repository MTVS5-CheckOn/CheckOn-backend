package com.checkon.counsel.integration.ai.dto;

import com.checkon.counsel.domain.GuardianLabelSuggestionKey;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record GuardianLabelConfirmationRequest(
	String kind,
	@JsonProperty("suggestion_id") String suggestionId,
	Action action,
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonProperty("corrected_value") CorrectedValue correctedValue
) {
	private static final String LABEL_KIND = "label";

	public static GuardianLabelConfirmationRequest confirmed(String suggestionId) {
		GuardianLabelSuggestionKey.parse(suggestionId);
		return new GuardianLabelConfirmationRequest(LABEL_KIND, suggestionId, Action.confirmed, null);
	}

	public static GuardianLabelConfirmationRequest corrected(String suggestionId, GuardianLabelValue value) {
		var key = GuardianLabelSuggestionKey.parse(suggestionId);
		if (value == null || value.axis() != key.axis()) {
			throw new IllegalArgumentException("corrected label value does not belong to the suggested axis");
		}
		return new GuardianLabelConfirmationRequest(
			LABEL_KIND, suggestionId, Action.corrected, new CorrectedValue(value)
		);
	}

	public static GuardianLabelConfirmationRequest rejected(String suggestionId) {
		GuardianLabelSuggestionKey.parse(suggestionId);
		return new GuardianLabelConfirmationRequest(LABEL_KIND, suggestionId, Action.rejected, null);
	}

	public enum Action {
		confirmed,
		corrected,
		rejected
	}

	public record CorrectedValue(GuardianLabelValue value) {
	}
}
