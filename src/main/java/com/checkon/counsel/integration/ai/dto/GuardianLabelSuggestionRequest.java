package com.checkon.counsel.integration.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GuardianLabelSuggestionRequest(
	@JsonProperty("guardian_ref") String guardianRef,
	List<HistoryRecord> history
) {
	public record HistoryRecord(
		@JsonProperty("record_id") String recordId,
		Direction direction,
		String text,
		OffsetDateTime at
	) {
	}

	public enum Direction {
		inbound,
		outbound
	}
}
