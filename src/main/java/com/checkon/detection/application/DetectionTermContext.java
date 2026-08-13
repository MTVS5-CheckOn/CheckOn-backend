package com.checkon.detection.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DetectionTermContext {
	NORMAL("normal"),
	NEW_TERM("new_term"),
	VACATION("vacation");

	private final String value;

	DetectionTermContext(String value) {
		this.value = value;
	}

	@JsonValue
	public String value() {
		return value;
	}

	@JsonCreator
	public static DetectionTermContext from(String value) {
		for (DetectionTermContext context : values()) {
			if (context.value.equals(value)) return context;
		}
		throw new IllegalArgumentException("unsupported term context: " + value);
	}
}
