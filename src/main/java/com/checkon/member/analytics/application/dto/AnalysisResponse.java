package com.checkon.member.analytics.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /member/parents/me/children/{studentId}/analysis?month=} 응답.
 *
 * <p>🔴 <b>전국 백분위 필드가 없다</b> — CheckOn-AI 미산출 목록에 있고 계약도 만들지 않았다.
 * 여기 존재하면 이 PR 은 반려다.</p>
 */
public record AnalysisResponse(
	String month,
	String calculationVersion,
	Instant calculatedAt,
	Overall overall,
	List<WeaknessCellPayload> weaknessRanking,
	WeaknessCellPayload primaryWeakness
) {

	public record Overall(
		String status,
		BigDecimal accuracyRate,
		Integer scoredCount,
		Integer averageActiveSeconds
	) {
	}

	public record WeaknessCellPayload(
		UUID teacherId,
		String areaTag,
		String typeTag,
		String status,
		int scoredCount,
		int correctCount,
		BigDecimal accuracyRate,
		Improvement improvement
	) {
	}

	public record Improvement(
		String status,
		BigDecimal previousAccuracyRate,
		BigDecimal accuracyDeltaPp,
		int minimumSampleSize
	) {
	}
}
