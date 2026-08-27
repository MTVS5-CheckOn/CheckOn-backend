package com.checkon.member.analytics.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkon.member.analytics.application.dto.AnalysisResponse.Improvement;

/**
 * {@code GET /member/parents/me/children/{studentId}/analysis/weaknesses/{areaTag}/{typeTag}}
 * 응답. 셀 하나 + 최근 문항 근거.
 *
 * <p>🔴 <b>상한으로 잘랐으면 {@link Truncated} 로 밝힌다</b>. 조용한 상한 절단 금지.</p>
 */
public record WeaknessDetailResponse(
	String areaTag,
	String typeTag,
	String status,
	int scoredCount,
	int correctCount,
	BigDecimal accuracyRate,
	Improvement improvement,
	List<RecentItem> recentItems,
	Truncated truncated
) {

	public record RecentItem(
		UUID itemId,
		UUID recordId,
		boolean correct,
		int activeElapsedSeconds,
		Instant occurredAt
	) {
	}

	public record Truncated(boolean applied, Integer limit, String orderedBy) {
	}
}
