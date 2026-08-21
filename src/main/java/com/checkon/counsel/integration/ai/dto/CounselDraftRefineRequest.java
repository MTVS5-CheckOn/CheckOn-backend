package com.checkon.counsel.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /v1/counsel/drafts/{job_id}/refine}.
 *
 * <p>No other fields are accepted ({@code extra="forbid"} on the AI side) —
 * there is no body field, so a teacher's hand-edited text is never sent and
 * is overwritten by the next refine turn (§③-6).
 *
 * <p>{@code turn_no} is a plain {@code int} on the AI side (default 1), not
 * {@code int|None} — sending it as an explicit JSON {@code null} is a type
 * violation and gets rejected with 400 before any LLM call runs (AI-A
 * 2026-08-21 정정). Omitting the key entirely is the correct way to fall
 * back to the AI's own default.
 */
public record CounselDraftRefineRequest(
	String instruction,
	@JsonProperty("turn_no") @JsonInclude(JsonInclude.Include.NON_NULL) Integer turnNo
) {
}
