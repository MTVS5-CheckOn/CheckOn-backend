package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Teacher's response to a classify suggestion via {@code POST /v1/confirmations}.
 * {@code rejected} is not a supported value in v1 — sending it is a 400
 * ({@code action_not_supported}), so it is deliberately not a constant here.
 */
public enum ConfirmationAction {
	CONFIRMED,
	CORRECTED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static ConfirmationAction fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
