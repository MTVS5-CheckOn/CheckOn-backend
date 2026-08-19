package com.checkon.counsel.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shared {@code meta} shape across all three counsel envelope responses.
 *
 * <p>{@code executionId} is the AI ledger key for "why did this draft come
 * out this way" lookups — except for the two worker-skipping paths
 * ({@code topic=schedule}, zero citable evidence), where it is a correlation
 * id only and ledger lookups return nothing (§0-2, §2 of the contract).
 */
public record CounselMeta(
	@JsonProperty("execution_id") String executionId,
	Versions versions
) {
	/** Declares which versions this endpoint/capability runs on — not what a given run actually used. */
	public record Versions(
		String pipeline,
		String engine,
		String threshold,
		String prompt,
		String schema,
		String contract,
		String graph,
		String taxonomy,
		@JsonProperty("verify_config") String verifyConfig,
		@JsonProperty("difficulty_calib") String difficultyCalib
	) {
	}
}
