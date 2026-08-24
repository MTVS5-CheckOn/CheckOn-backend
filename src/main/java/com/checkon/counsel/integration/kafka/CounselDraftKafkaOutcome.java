package com.checkon.counsel.integration.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload of the {@code counsel-draft.completed} event — an ID reference
 * only, per the counsel contract's own appendix note: "payload에 초안
 * 본문·문의 원문을 싣지 않습니다 — ID 참조만입니다. 본문은 REST GET으로
 * 회수합니다(risk-detection과 같은 규약)." The draft body/citations/text are
 * fetched afterward via {@code GET /v1/counsel/drafts/{ai_job_id}}, not
 * carried here.
 */
public record CounselDraftKafkaOutcome(
	@JsonProperty("job_id") String jobId,
	String status,
	@JsonProperty("execution_id") String executionId
) {
}
