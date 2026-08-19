package com.checkon.counsel.integration.ai.dto;

import com.checkon.counsel.domain.CounselJobPhase;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 202 response for {@code POST /v1/counsel/drafts}. {@code data} carries only
 * {@code job_id}/{@code status} — the draft body is never in this envelope,
 * it is only ever fetched via the GET endpoint (§0-1).
 */
public record CounselDraftCreateResponse(
	Data data,
	CounselApiError error,
	CounselMeta meta
) {

	public record Data(
		@JsonProperty("job_id") String jobId,
		CounselJobPhase status
	) {
	}
}
