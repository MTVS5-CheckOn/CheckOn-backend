package com.checkon.detection.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DetectionResultEvidenceDraft(
	UUID id,
	String sourceHint,
	String recordId,
	String summary,
	String role,
	BigDecimal observed,
	Integer sampleSize,
	LocalDate occurredOn
) {
	public DetectionResultEvidenceDraft(
		UUID id, String sourceHint, String recordId, String summary
	) {
		this(id, sourceHint, recordId, summary, "trigger", null, null, null);
	}
}
