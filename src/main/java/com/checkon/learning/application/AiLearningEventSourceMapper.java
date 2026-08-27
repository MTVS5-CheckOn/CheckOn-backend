package com.checkon.learning.application;

/** Separates backend provenance values from the external AI source enum. */
final class AiLearningEventSourceMapper {

	private AiLearningEventSourceMapper() {
	}

	static String toAiSource(String internalSourceType) {
		return switch (internalSourceType) {
			case "MANUAL" -> "MANUAL";
			case "member_attempt_item", "member_attempt" -> "studentHome";
			case null -> throw unsupported("null");
			default -> throw unsupported(internalSourceType);
		};
	}

	private static IllegalStateException unsupported(String sourceType) {
		return new IllegalStateException(
			"Unsupported internal learning record source_type: " + sourceType
		);
	}
}
