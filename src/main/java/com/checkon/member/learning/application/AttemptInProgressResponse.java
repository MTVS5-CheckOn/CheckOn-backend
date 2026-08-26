package com.checkon.member.learning.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 진행 중 attempt 의 응답. 🔴 정답 번호·해설·정오 판정 필드를 담지 않는다(절대 규칙 4).
 *
 * <p>{@code SUBMITTED} (채점 중 크래시 잔여)는 이 record 로 낼 수 없으므로 서비스가
 * ATTEMPT_ALREADY_SUBMITTED 오류로 돌린다.</p>
 */
public record AttemptInProgressResponse(
	UUID attemptId,
	UUID assignmentId,
	String status,
	int version,
	int itemCount,
	int totalActiveElapsedSeconds,
	Instant startedAt,
	Instant lastProgressAt,
	List<AttemptInProgressItem> items,
	List<AttemptAnswerSnapshot> answers
) {
}
