package com.checkon.counsel.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A reference-list entry, not a footnote — there is no mapping between a body
 * sentence and a citation (§②-3). The router echoes the request's
 * {@code context.facts} in full, so an unmentioned fact can still appear here.
 */
public record CounselCitation(
	@JsonProperty("cite_id") String citeId,
	@JsonProperty("record_id") String recordId,
	String summary
) {
}
