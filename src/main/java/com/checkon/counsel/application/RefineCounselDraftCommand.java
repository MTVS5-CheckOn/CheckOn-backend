package com.checkon.counsel.application;

/** Input to {@link CounselDraftService#refine}. */
public record RefineCounselDraftCommand(
	String tenantAlias,
	String requestId,
	String idempotencyKey,
	String jobId,
	String instruction,
	Integer turnNo
) {
}
