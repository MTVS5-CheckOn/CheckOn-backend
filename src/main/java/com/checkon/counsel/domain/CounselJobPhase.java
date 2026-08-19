package com.checkon.counsel.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Job phase — 7 values, frozen by the AI contract appendix A. Distinct from
 * {@link CounselDraftStatus}: a {@code SUCCEEDED} job can still carry a
 * rejected/failed draft (§②-1 of the 2026-08-19 counsel contract).
 */
public enum CounselJobPhase {
	QUEUED,
	LEASED,
	RUNNING,
	PAUSED,
	SUCCEEDED,
	FAILED,
	CANCELLED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CounselJobPhase fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
