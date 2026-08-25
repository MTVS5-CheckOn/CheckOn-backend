package com.checkon.counsel.integration.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.fasterxml.jackson.annotation.JsonProperty;

public record GuardianLabelSuggestionResponse(
	Data data,
	CounselApiError error,
	CounselMeta meta
) {
	public record Data(List<Suggestion> suggestions) {
	}

	public record Suggestion(
		@JsonProperty("suggestion_id") String suggestionId,
		@JsonProperty("guardian_ref") String guardianRef,
		Label label,
		BigDecimal confidence,
		@JsonProperty("evidence_quotes") List<EvidenceQuote> evidenceQuotes
	) {
	}

	public record Label(GuardianLabelAxis axis, GuardianLabelValue value) {
		public Label {
			if (axis == null || value == null || value.axis() != axis) {
				throw new IllegalArgumentException("guardian label value does not belong to its axis");
			}
		}
	}

	public record EvidenceQuote(
		@JsonProperty("record_id") String recordId,
		String quote
	) {
	}
}
