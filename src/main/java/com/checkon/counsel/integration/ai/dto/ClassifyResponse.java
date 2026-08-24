package com.checkon.counsel.integration.ai.dto;

import java.math.BigDecimal;

import com.checkon.counsel.domain.ClassifyFallbackReason;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.domain.InquirySentiment;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 200 response for {@code POST /v1/classify} — always 200, even when the
 * inquiry could not be classified ({@code classified:false}); that is a
 * normal outcome with fixed placeholder values, not an error.
 *
 * <p>Same {@code {data, error, meta}} envelope as counsel, so
 * {@link CounselApiError} and {@link CounselMeta} are reused as-is.
 */
public record ClassifyResponse(
	Data data,
	CounselApiError error,
	CounselMeta meta
) {

	public record Data(
		@JsonProperty("inquiry_ref") String inquiryRef,
		CounselTopic topic,
		InquirySentiment sentiment,
		CounselUrgency urgency,
		Confidence confidence,
		boolean classified,
		@JsonProperty("fallback_reason") ClassifyFallbackReason fallbackReason
	) {
	}

	/**
	 * Self-reported, uncalibrated per-axis confidence (§2-3 of the classify
	 * contract) — not a probability. No recommended threshold exists yet;
	 * do not branch UI on these numbers until one is set from observed
	 * correction rates.
	 */
	public record Confidence(
		BigDecimal topic,
		BigDecimal sentiment,
		BigDecimal urgency
	) {
	}
}
