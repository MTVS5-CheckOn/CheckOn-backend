package com.checkon.member.consultation.domain;

import java.time.Instant;
import java.util.UUID;

public record ConsultationMessageRecord(
	UUID id,
	UUID consultationId,
	ConsultationAuthorRole authorRole,
	String content,
	Instant publishedAt
) {
}
