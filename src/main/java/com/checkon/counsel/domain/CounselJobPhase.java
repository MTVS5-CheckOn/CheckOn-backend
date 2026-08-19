package com.checkon.counsel.domain;

import java.util.Locale;
import java.util.Set;

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

	private static final Set<CounselJobPhase> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

	/** {@code true} once a poller should stop and GET once more instead of retrying. */
	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}

	@JsonValue
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CounselJobPhase fromWireValue(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
