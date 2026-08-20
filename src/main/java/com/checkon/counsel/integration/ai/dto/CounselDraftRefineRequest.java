package com.checkon.counsel.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /v1/counsel/drafts/{job_id}/refine}.
 *
 * <p>No other fields are accepted ({@code extra="forbid"} on the AI side) —
 * there is no body field, so a teacher's hand-edited text is never sent and
 * is overwritten by the next refine turn (§③-6).
 */
public record CounselDraftRefineRequest(
	String instruction,
	@JsonProperty("turn_no") Integer turnNo
) {
}
