package com.checkon.member.question.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code member_question_messages} 한 행. */
public record QuestionMessageRecord(
	UUID id,
	UUID questionId,
	QuestionAuthorRole authorRole,
	UUID authorAccountId,
	String content,
	Instant publishedAt
) {
}
