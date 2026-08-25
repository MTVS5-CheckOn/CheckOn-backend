package com.checkon.counsel.domain;

public record GuardianLabelSuggestionKey(
	String guardianRef,
	GuardianLabelAxis axis,
	GuardianLabelValue value
) {

	public static GuardianLabelSuggestionKey parse(String suggestionId) {
		if (suggestionId == null || suggestionId.isBlank()) {
			throw new IllegalArgumentException("suggestionId must not be blank");
		}
		int valueSeparator = suggestionId.lastIndexOf(':');
		int axisSeparator = valueSeparator < 0 ? -1 : suggestionId.lastIndexOf(':', valueSeparator - 1);
		if (axisSeparator <= 0 || valueSeparator <= axisSeparator + 1 || valueSeparator == suggestionId.length() - 1) {
			throw new IllegalArgumentException("suggestionId must be guardian_ref:axis:value");
		}
		String guardianRef = suggestionId.substring(0, axisSeparator);
		GuardianLabelAxis axis = GuardianLabelAxis.fromWireValue(
			suggestionId.substring(axisSeparator + 1, valueSeparator)
		);
		GuardianLabelValue value = GuardianLabelValue.fromWireValue(suggestionId.substring(valueSeparator + 1));
		if (value.axis() != axis) throw new IllegalArgumentException("suggested label value does not belong to its axis");
		return new GuardianLabelSuggestionKey(guardianRef, axis, value);
	}
}
