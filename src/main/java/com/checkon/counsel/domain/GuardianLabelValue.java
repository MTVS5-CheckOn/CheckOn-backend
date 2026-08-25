package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GuardianLabelValue {
	DATA(GuardianLabelAxis.COMM),
	NARRATIVE(GuardianLabelAxis.COMM),
	ANXIOUS(GuardianLabelAxis.SENSITIVITY),
	DIRECT(GuardianLabelAxis.SENSITIVITY),
	GRADE(GuardianLabelAxis.INTEREST),
	ATTITUDE(GuardianLabelAxis.INTEREST),
	ADMISSION(GuardianLabelAxis.INTEREST),
	FREQUENT(GuardianLabelAxis.FREQUENCY),
	MONTHLY(GuardianLabelAxis.FREQUENCY);

	private final GuardianLabelAxis axis;

	GuardianLabelValue(GuardianLabelAxis axis) {
		this.axis = axis;
	}

	public GuardianLabelAxis axis() {
		return axis;
	}

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static GuardianLabelValue fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
