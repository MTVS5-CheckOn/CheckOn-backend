package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Inquiry topic — the AI-confirmed 4-value enum (§4 of the 2026-08-19 counsel
 * contract). {@code complaint} is not a topic; it moved to
 * {@code POST /v1/classify}'s {@code sentiment}.
 */
public enum CounselTopic {
	GRADE,
	SCHEDULE,
	COUNSEL_REQUEST,
	ETC;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CounselTopic fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
