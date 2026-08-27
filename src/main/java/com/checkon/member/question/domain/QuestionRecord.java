package com.checkon.member.question.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_questions} 한 행의 도메인 표현. Controller 응답과 다르다 —
 * 응답은 조립된 뷰이고, 이건 저장된 사실이다.
 */
public record QuestionRecord(
	UUID id,
	UUID studentId,
	UUID teacherId,
	UUID assignmentId,
	UUID attemptId,
	UUID itemId,
	String title,
	String content,
	QuestionStatus status,
	int followUpCount,
	Instant createdAt,
	Instant answeredAt
) {
}
