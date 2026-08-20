package com.checkon.counsel.integration.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.checkon.counsel.domain.CounselDraftStatus;
import com.checkon.counsel.domain.CounselJobPhase;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 200 response for {@code GET /v1/counsel/drafts/{job_id}}. */
public record CounselDraftGetResponse(
	Data data,
	CounselApiError error,
	CounselMeta meta
) {

	/**
	 * {@code status} (job phase) and {@code result.draftStatus} (draft verdict)
	 * are different axes — {@code status=SUCCEEDED} with
	 * {@code result.draftStatus=REJECTED_INSUFFICIENT} is a normal combination,
	 * not an error (§①).
	 */
	public record Data(
		@JsonProperty("job_id") String jobId,
		CounselJobPhase status,
		Result result
	) {
	}

	public record Result(
		@JsonProperty("draft_status") CounselDraftStatus draftStatus,
		String text,
		List<CounselCitation> citations,
		@JsonProperty("labels_applied") List<String> labelsApplied,
		@JsonProperty("label_suggestions") List<LabelSuggestion> labelSuggestions,
		/**
		 * Reason prefix — 14 known values plus an open {@code unmapped:} prefix.
		 * This is deliberately a raw string, not an enum: the AI contract states
		 * this set can grow. Map known values to display text and fall back to
		 * {@link CounselDraftStatus}'s default copy for anything unrecognized
		 * (§②-2-1, appendix B-⑧).
		 */
		@JsonProperty("status_reason") String statusReason,
		@JsonProperty("generated_at") OffsetDateTime generatedAt
	) {
	}

	/** Always an empty list in v1 — the suggestion generator is unimplemented. */
	public record LabelSuggestion(String axis, String value) {
	}
}
