package com.checkon.member.question.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.question.domain.QuestionStatus;

/**
 * 계약 {@code StudentQuestionDetail} (member-api.yaml:2041-2050).
 * {@code allOf: StudentQuestion + {content, messages}}.
 */
public record StudentQuestionDetailResponse(
	UUID questionId,
	UUID assignmentId,
	UUID itemId,
	Integer itemOrdinal,
	String worksheetTitle,
	String title,
	QuestionStatus status,
	Instant createdAt,
	Instant answeredAt,
	MemberTeacherSummary teacher,
	String content,
	List<QuestionMessageResponse> messages
) {
}
