package com.checkon.member.learning.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_learning_sessions} 한 행. 채점 완료 후 학부모·강사 화면이 여기를 읽는다 —
 * 문항 본문·정답이 없는 요약이라 정책 격리로 충분하다.
 */
public record MemberLearningSession(
	UUID id,
	UUID attemptId,
	UUID studentId,
	UUID teacherId,
	UUID assignmentId,
	String titleText,
	int itemCount,
	int correctCount,
	int activeElapsedSec,
	UUID submitRecordId,
	Instant occurredAt
) {
}
