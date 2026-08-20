package com.checkon.counsel.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /v1/classify}. Exactly these two fields —
 * the AI side uses {@code extra="forbid"}, so any other field is a 400.
 *
 * <p>{@code bodyText} is the raw inquiry text, the opposite of counsel's
 * {@code text_masked}: classify masks it internally
 * ({@code runtime/redaction}) and falls back without calling the LLM if
 * anything unredactable remains. Sending an already-masked string here
 * defeats that redaction step.
 */
public record ClassifyRequest(
	@JsonProperty("inquiry_ref") String inquiryRef,
	@JsonProperty("body_text") String bodyText
) {
}
