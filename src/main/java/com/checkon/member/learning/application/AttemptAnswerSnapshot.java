package com.checkon.member.learning.application;

import java.util.UUID;

/**
 * 진행 중 attempt 응답에 함께 내려가는 답안 스냅샷 한 건. 선택 번호와 활성 경과만 담는다.
 *
 * <p>{@code selectedNo} 는 미응답이면 {@code null} 이다 — 미응답과 잘못된 응답을 구분한다.
 * {@code activeElapsedSec} 는 서버가 검증하고 가산한 값이다(클라이언트 절대 시각은 저장하지 않는다).</p>
 */
public record AttemptAnswerSnapshot(
	UUID itemId,
	Integer selectedNo,
	int activeElapsedSec,
	int revision
) {
}
