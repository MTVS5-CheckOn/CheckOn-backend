package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why {@code POST /v1/classify} could not classify an inquiry
 * ({@code classified:false}) — a closed 2-value enum, unlike counsel's
 * open-ended {@code status_reason} string (classify/confirmations contract §3-2).
 */
public enum ClassifyFallbackReason {
	/** The LLM output could not be forced into the enum schema after the retry cap (2). */
	PARSE_EXHAUSTED,
	/** A pre-send tripwire found leftover PII in the prompt; the LLM was never called. */
	TRIPWIRE_BLOCKED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static ClassifyFallbackReason fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
