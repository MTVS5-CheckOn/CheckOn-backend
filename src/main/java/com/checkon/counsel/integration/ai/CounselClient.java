package com.checkon.counsel.integration.ai;

import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;

/**
 * Backend-to-AI client for the counsel draft flow (§0 of the 2026-08-20
 * counsel contract). Draft creation is now Kafka-async (adapter-consumed
 * request, Kafka-notified completion) — only GET and refine remain direct
 * synchronous REST calls (appendix B-①, as revised by the 8/20 Kafka-철회
 * document).
 */
public interface CounselClient {

	/**
	 * {@code GET /v1/counsel/drafts/{jobId}}. Never runs the job — it only
	 * reads the current phase, so polling a stuck {@code queued} job is
	 * pointless; only another POST can advance it (§0-3).
	 */
	CounselDraftGetResponse getDraft(String jobId, String tenantAlias, String requestId);

	/**
	 * {@code POST /v1/counsel/drafts/{jobId}/refine}. {@code Idempotency-Key}
	 * is mandatory here (unlike create) — omitting it is a 400 (§③-5).
	 */
	CounselDraftRefineResponse refineDraft(String jobId, CounselDraftRefineRequest request, RequestHeaders headers);

	record RequestHeaders(String tenantAlias, String requestId, String idempotencyKey) {
	}
}
