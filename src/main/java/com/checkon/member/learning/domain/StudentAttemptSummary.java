package com.checkon.member.learning.domain;

import java.util.UUID;

/**
 * 한 학생·한 assignment 의 최신 attempt 요약. 학습지 목록의 {@code status}·{@code latestAttemptId}·
 * {@code accuracyRate} 를 계산할 때 쓴다.
 *
 * <p>🔴 {@code accuracyRate} 는 {@code SCORED} 일 때만 채워진다 — 그 외는 {@code null} 이다.
 * 미응답이 있어도 서버 정본으로 계산된 값이 여기 그대로 담긴다.</p>
 */
public record StudentAttemptSummary(
	UUID assignmentId,
	UUID attemptId,
	MemberAttemptStatus status,
	Double accuracyRate
) {
}
