package com.checkon.counsel.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;

/**
 * Teacher-facing input to {@link CounselDraftRequestService#createDraft} —
 * real roster UUIDs and raw (unmasked) text, as the frontend would send them.
 * {@link CounselDraftRequestService} resolves aliases and masks the text
 * before handing off to {@link CounselDraftService}.
 */
public record CreateCounselInquiryCommand(
	UUID studentId,
	UUID classId,
	String idempotencyKey,
	String inquiryRef,
	CounselTopic topic,
	CounselUrgency urgency,
	OffsetDateTime receivedAt,
	String rawText,
	List<String> labels,
	List<CreateCounselDraftCommand.DismissedSuggestion> dismissedSuggestions,
	String periodLabel,
	List<CreateCounselDraftCommand.Fact> facts
) {
}
