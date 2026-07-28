package com.checkon.detection.domain;

import java.util.UUID;

public record DetectionResultEvidenceDraft(
	UUID id,
	String sourceHint,
	String recordId,
	String summary
) {
}
