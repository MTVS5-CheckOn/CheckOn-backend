package com.checkon.counsel.integration.ai;

import com.checkon.counsel.integration.ai.dto.CounselDraftCreateRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;

/**
 * Backend-to-AI client for the counsel draft flow (§0 of the 2026-08-20
 * counsel contract). No Kafka is involved — the backend calls these three
 * REST endpoints directly and synchronously (appendix B-①).
 */
public interface CounselClient {

	/**
	 * {@code POST /v1/counsel/drafts}. Runs its own job to completion inline
	 * (K=1 as of 8/20 — a pending job ahead of yours still returns
	 * {@code queued} instead of being drained, §0-3). Callers must set a read
	 * timeout of 480s or more (§0-4, LLM call cap raised to 90s).
	 */
	CounselDraftCreateResponse createDraft(CounselDraftCreateRequest request, RequestHeaders headers);

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
