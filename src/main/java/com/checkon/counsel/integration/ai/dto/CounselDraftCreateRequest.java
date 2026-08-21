package com.checkon.counsel.integration.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST /v1/counsel/drafts}. */
public record CounselDraftCreateRequest(
	Inquiry inquiry,
	@JsonProperty("student_ref") String studentRef,
	@JsonProperty("parent_ref") String parentRef,
	@JsonProperty("class_ref") String classRef,
	List<String> labels,
	@JsonProperty("dismissed_suggestions") List<DismissedSuggestion> dismissedSuggestions,
	Context context
) {

	public record Inquiry(
		@JsonProperty("inquiry_ref") String inquiryRef,
		CounselTopic topic,
		CounselUrgency urgency,
		@JsonProperty("received_at") OffsetDateTime receivedAt,
		@JsonProperty("text_masked") String textMasked
	) {
	}

	/** A label suggestion the teacher already dismissed, so the AI does not repeat it. */
	public record DismissedSuggestion(String axis, String value) {
	}

	/**
	 * The evidence package — the entire universe of facts a draft may cite.
	 * {@code facts} must exclude {@code role=baseline} rows (§7): the gate's
	 * allowed-number set is derived from this array in full, so a baseline
	 * number left in lets the LLM use it anywhere and passes the gate.
	 */
	public record Context(
		@JsonProperty("snapshot_hash") String snapshotHash,
		@JsonProperty("period_label") String periodLabel,
		List<Fact> facts
	) {
	}

	/** {@code recordId} is {@code null} for aggregate/baseline-derived facts — that is normal, do not fabricate an id. */
	public record Fact(
		@JsonProperty("record_id") String recordId,
		String summary
	) {
	}
}
