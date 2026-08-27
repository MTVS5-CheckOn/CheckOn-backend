package com.checkon.member.learning.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 진행 중 attempt 의 응답. 🔴 정답 번호·해설·정오 판정 필드를 담지 않는다(절대 규칙 4).
 *
 * <p>계약({@code member-api.yaml AttemptInProgress})의 필드 순서를 그대로 따른다.
 * {@code SUBMITTED}·{@code SCORED} 는 이 record 로 낼 수 없으므로 서비스가
 * {@code ATTEMPT_ALREADY_SUBMITTED}(409)로 돌린다 — S4(제출·채점) 이후에는 SCORED 는
 * {@code AttemptResult} 로 분기한다.</p>
 */
public record AttemptInProgressResponse(
	UUID attemptId,
	UUID assignmentId,
	String status,
	int version,
	String snapshotHash,
	UUID currentItemId,
	int totalActiveElapsedSeconds,
	Instant startedAt,
	List<AttemptInProgressItem> items,
	Map<UUID, Integer> answers,
	Map<UUID, Integer> activeElapsedSecondsByItem
) {
}
