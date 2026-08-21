package com.checkon.counsel.integration.ai;

import com.checkon.counsel.integration.ai.dto.ClassifyRequest;
import com.checkon.counsel.integration.ai.dto.ClassifyResponse;
import com.checkon.counsel.integration.ai.dto.ConfirmationRequest;
import com.checkon.counsel.integration.ai.dto.ConfirmationResponse;

/**
 * Backend-to-AI client for inquiry classification and its correction loop
 * (classify/confirmations contract, 2026-08-20). Both calls are synchronous
 * 200s — no job/polling like counsel, and neither accepts an
 * {@code Idempotency-Key} (classify is side-effect-free and cached by
 * {@code (tenant_id, inquiry_ref)}; confirmations diffs against the stored
 * prediction so a resend of the same value is a no-op).
 */
public interface ClassifyClient {

	/**
	 * {@code POST /v1/classify}. Cached by {@code (tenant_id, inquiry_ref)} on
	 * the AI side — calling this again after a teacher correction returns the
	 * stale pre-correction prediction, not the correction. Never use this to
	 * "refresh" a corrected value.
	 */
	ClassifyResponse classify(ClassifyRequest request, String tenantAlias, String requestId);

	/**
	 * {@code POST /v1/confirmations}. 404s if this {@code inquiry_ref} was
	 * never classified, or classify returned {@code classified:false}
	 * (fallback results are not stored). When a teacher corrects the topic,
	 * callers must also re-create the counsel draft with a new
	 * {@code Idempotency-Key} and the corrected topic (classify/confirmations
	 * contract §4-2) — this call alone does not touch any draft.
	 */
	ConfirmationResponse confirm(ConfirmationRequest request, String tenantAlias, String requestId);
}
