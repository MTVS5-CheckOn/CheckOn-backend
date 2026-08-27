package com.checkon.member.consultation.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.consultation.domain.ConsultationAuthorRole;

public record ConsultationMessageResponse(
	UUID messageId,
	ConsultationAuthorRole authorRole,
	String content,
	Instant publishedAt
) {
}
