package com.checkon.member.question.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.question.domain.QuestionAuthorRole;

/** 계약 {@code QuestionMessage} (member-api.yaml:2052-2059). */
public record QuestionMessageResponse(
	UUID messageId,
	QuestionAuthorRole authorRole,
	String content,
	Instant publishedAt
) {
}
