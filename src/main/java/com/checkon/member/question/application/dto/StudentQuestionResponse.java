package com.checkon.member.question.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.question.domain.QuestionStatus;

/**
 * 계약 {@code StudentQuestion} (member-api.yaml:2026-2039).
 *
 * <p>🔴 본문·정답·해설을 담지 않는다 — {@code itemOrdinal} 만 내려보낸다(PR6 지시서 §3).</p>
 */
public record StudentQuestionResponse(
	UUID questionId,
	UUID assignmentId,
	UUID itemId,
	Integer itemOrdinal,
	String worksheetTitle,
	String title,
	QuestionStatus status,
	Instant createdAt,
	Instant answeredAt,
	MemberTeacherSummary teacher
) {
}
