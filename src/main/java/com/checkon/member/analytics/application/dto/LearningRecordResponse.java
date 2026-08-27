package com.checkon.member.analytics.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET .../learning-records[/{recordId}]} 응답. 목록/상세 공통 요약 + 상세용 items·weakness·trend.
 *
 * <p>🔴 <b>목록에는 attempt 기반 세션만 나온다</b> — 엑셀 원본은 여기에 없고 월 집계에는 들어간다.
 * 그 비대칭은 조회 서비스 주석에 남긴다(계약 참조).</p>
 *
 * <p>🔴 {@code trend} 는 상세 응답에만 채운다. 목록에서는 {@link List#of()} 로 비운다 — 계약의
 * {@code LearningRecordSummary} 에는 필드가 없고 {@code LearningRecordDetail} 만 요구한다.</p>
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
	String weaknessStatus,
	List<TrendPoint> trend
) {

	/**
	 * 학습기록 상세 응답의 월별 추이 한 원소. 계약(<code>member-api.yaml:2015-2024</code>)의
	 * {@code LearningRecordDetail.trend[]} 와 1:1.
	 *
	 * <p>🔴 값이 없으면 {@code accuracyRate} 는 {@code null} 이고 {@code status} 로 이유를 낸다.
	 * {@code 0.0} 으로 채우면 「변화 없음」이라는 거짓말이 된다.</p>
	 */
	public record TrendPoint(String month, BigDecimal accuracyRate, String status) {
	}
}
