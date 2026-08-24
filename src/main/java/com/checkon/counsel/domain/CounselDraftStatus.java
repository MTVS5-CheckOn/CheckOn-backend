package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Draft verdict — 5 values, frozen by the AI contract appendix A.
 * {@code REJECTED_INSUFFICIENT} and {@code TEMPLATE_ONLY} are honest, normal
 * outcomes, not errors — never render them with error UI (§②-2, §②-2-1).
 */
public enum CounselDraftStatus {
	GENERATED,
	TEMPLATE_ONLY,
	REJECTED_INSUFFICIENT,
	LLM_FAILED,
	GATE_EXHAUSTED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CounselDraftStatus fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
