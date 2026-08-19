package com.checkon.counsel.integration.ai.dto;

import java.util.List;

import com.checkon.counsel.domain.CounselBlockedReason;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 200 response for {@code POST /v1/counsel/drafts/{job_id}/refine}. A gate
 * block is {@code applied:false} in a 200, never a 4xx/5xx — branch on the
 * {@code applied} flag, not the HTTP status (§③-2).
 */
public record CounselDraftRefineResponse(
	Data data,
	CounselApiError error,
	CounselMeta meta
) {

	public record Data(
		boolean applied,
		String text,
		List<CounselCitation> citations,
		@JsonProperty("blocked_reason") CounselBlockedReason blockedReason
	) {
	}
}
