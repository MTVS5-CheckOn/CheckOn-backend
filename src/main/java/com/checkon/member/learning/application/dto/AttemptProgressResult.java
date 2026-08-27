package com.checkon.member.learning.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * progress 자동저장 응답. 계약 {@code AttemptProgressResult}. {@code duplicated=true} 는
 * 같은 {@code clientSequence} 재전송을 무시했다는 뜻이며 오류가 아니다(분기표 §1 progress).
 */
public record AttemptProgressResult(
	UUID attemptId,
	int version,
	int totalActiveElapsedSeconds,
	Instant savedAt,
	boolean duplicated
) {
}
