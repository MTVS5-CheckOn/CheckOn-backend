package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Refine block reason — 8 values, frozen by the AI contract appendix A.
 * A refine block is {@code 200 applied:false}, never a 4xx/5xx (§③-2).
 *
 * <p>{@code TONE_VIOLATION} is a confluence point: length, symbol, banned
 * word, and leaked-instruction gate failures all surface here too (§③-3-1).
 * Display text is backend-owned; the AI intentionally does not send one.
 */
public enum CounselBlockedReason {
	EVIDENCE_MISSING,
	COMPARISON_EXPOSURE,
	TONE_VIOLATION,
	PII_EXPOSURE,
	OUT_OF_SCOPE,
	ANSWER_INTEGRITY,
	BANNED_TOPIC,
	PROMPT_INJECTION;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CounselBlockedReason fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
