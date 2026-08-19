package com.checkon.counsel.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;

/**
 * Input to {@link CounselDraftService#createDraft}. Refs (student/parent/class/
 * inquiry) are expected to already be resolved, opaque aliases — this slice
 * only owns the AI communication, not alias issuance (see roster/problem
 * slices for that concern).
 */
public record CreateCounselDraftCommand(
	String tenantAlias,
	String requestId,
	String idempotencyKey,
	String inquiryRef,
	CounselTopic topic,
	CounselUrgency urgency,
	OffsetDateTime receivedAt,
	String textMasked,
	String studentRef,
	String parentRef,
	String classRef,
	List<String> labels,
	List<DismissedSuggestion> dismissedSuggestions,
	String snapshotHash,
	String periodLabel,
	List<Fact> facts
) {

	public record DismissedSuggestion(String axis, String value) {
	}

	/** {@code recordId} is {@code null} for aggregate/baseline-derived facts — that is normal. */
	public record Fact(String recordId, String summary) {
	}
}
