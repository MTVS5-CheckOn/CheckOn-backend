package com.checkon.member.learning.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_attempt_answers} 한 행. {@code selectedNo} 는 미응답이면 {@code null} 이다
 * ({@code ck_member_attempt_answers_selected} 는 1 이상 또는 NULL 만 허용).
 *
 * <p>{@code revision} 은 progress 자동저장이 UPSERT 할 때마다 +1 된다 — 진행 순서를 재구성할 때
 * 쓴다. attempt 자체의 optimistic lock 은 {@code MemberAttempt.version} 이다.</p>
 */
public record MemberAttemptAnswer(
	UUID attemptId,
	UUID itemId,
	Integer selectedNo,
	int activeElapsedSec,
	int revision,
	Instant updatedAt
) {
}
