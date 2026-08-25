package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GuardianLabelAxis {
	COMM,
	SENSITIVITY,
	INTEREST,
	FREQUENCY;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static GuardianLabelAxis fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
