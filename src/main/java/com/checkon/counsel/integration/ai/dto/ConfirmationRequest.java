package com.checkon.counsel.integration.ai.dto;

import com.checkon.counsel.domain.ConfirmationAction;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.domain.InquirySentiment;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /v1/confirmations}. {@code extra="forbid"}
 * applies here too. v1 only implements {@code kind="classification"} —
 * {@code tag}/{@code label}/{@code draft_edit} are 400
 * ({@code kind_not_implemented}) because the AI side has not built label
 * suggestions yet; that stays a BE-side concern until it does.
 */
public record ConfirmationRequest(
	String kind,
	@JsonProperty("suggestion_id") String suggestionId,
	ConfirmationAction action,
	@JsonProperty("corrected_value") CorrectedValue correctedValue
) {

	private static final String CLASSIFICATION_KIND = "classification";

	/** {@code action=CONFIRMED}: no correctedValue — sending one is a 400 ({@code corrected_value_not_allowed}). */
	public static ConfirmationRequest confirmed(String inquiryRef) {
		return new ConfirmationRequest(CLASSIFICATION_KIND, inquiryRef, ConfirmationAction.CONFIRMED, null);
	}

	/**
	 * {@code action=CORRECTED}: at least one axis of {@code correctedValue} must
	 * be non-null, or the AI rejects with 400 ({@code corrected_value_missing}).
	 * Partial correction (e.g. topic only) is fine — the other axes stay null.
	 */
	public static ConfirmationRequest corrected(
		String inquiryRef,
		CounselTopic topic,
		InquirySentiment sentiment,
		CounselUrgency urgency
	) {
		return new ConfirmationRequest(
			CLASSIFICATION_KIND, inquiryRef, ConfirmationAction.CORRECTED,
			new CorrectedValue(topic, sentiment, urgency)
		);
	}

	/** All three axes are independently nullable — this is a partial-correction patch, not a full replacement. */
	public record CorrectedValue(
		CounselTopic topic,
		InquirySentiment sentiment,
		CounselUrgency urgency
	) {
	}
}
