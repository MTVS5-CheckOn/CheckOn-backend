package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Inbox sort/SLA hint only. The AI does not consume this value to shape draft
 * tone — the 4-axis {@code labels[]} is the only path to tone (§1-⑥).
 */
public enum CounselUrgency {
	IMMEDIATE,
	NORMAL;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CounselUrgency fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
