package com.checkon.counsel.integration.ai.dto;

/** 200 response for {@code POST /v1/confirmations}. */
public record ConfirmationResponse(
	Data data,
	CounselApiError error,
	CounselMeta meta
) {

	public record Data(boolean accepted) {
	}
}
