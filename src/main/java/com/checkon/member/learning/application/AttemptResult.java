package com.checkon.member.learning.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 채점 완료된 attempt 의 결과. {@code GET .../attempts/{id}/result} 와 제출 응답이 공유한다.
 *
 * <p>{@code accuracyRate} 는 {@code correctCount / itemCount} 를 소수점 셋째 자리에서 잘라
 * {@code BigDecimal} 로 계산하되 직렬화는 {@code Double} 로 나가는 것을 허용한다.
 * 정확도는 서버 정본이다 — 브라우저가 다시 계산해 갈리면 서버 값이 이긴다.</p>
 *
 * <p>{@code learningRecordId} 는 제출 트랜잭션이 남긴 {@code learning_records} 의 SUBMIT 행
 * 식별자다. 학생 화면이 학습기록 상세로 즉시 이동할 때 쓴다. 재제출·재조회에서도 <b>같은 값</b>이
 * 나온다(멱등 응답의 저장된 body 를 그대로 재생).</p>
 */
public record AttemptResult(
	UUID attemptId,
	UUID assignmentId,
	String status,
	int itemCount,
	int correctCount,
	double accuracyRate,
	int totalActiveElapsedSeconds,
	Instant startedAt,
	Instant submittedAt,
	Instant scoredAt,
	UUID learningRecordId,
	List<AttemptItemResult> items
) {
}
