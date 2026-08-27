package com.checkon.member.analytics.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET .../learning-records[/{recordId}]} 응답. 목록/상세 공통 요약 + 상세용 items·weakness.
 *
 * <p>🔴 <b>목록에는 attempt 기반 세션만 나온다</b> — 엑셀 원본은 여기에 없고 월 집계에는 들어간다.
 * 그 비대칭은 조회 서비스 주석에 남긴다(계약 참조).</p>
 */
public record LearningRecordResponse(
	UUID recordId,
	UUID assignmentId,
	UUID attemptId,
	String title,
	Instant occurredAt,
	String month,
	int itemCount,
	int correctCount,
	BigDecimal accuracyRate,
	int totalActiveElapsedSeconds,
	List<UUID> itemIds,
	String weaknessStatus
) {
}
