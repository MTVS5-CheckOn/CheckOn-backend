package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The "what emotion was this written in" axis — separate from
 * {@link CounselTopic} ("what is this about"). Produced only by
 * {@code POST /v1/classify}; {@code complaint} moved here from topic on
 * 2026-08-05 (counsel contract §4).
 */
public enum InquirySentiment {
	NORMAL,
	COMPLAINT;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static InquirySentiment fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
