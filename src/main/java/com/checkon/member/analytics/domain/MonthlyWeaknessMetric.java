package com.checkon.member.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_monthly_weakness_metrics} 한 행 (셀).
 *
 * <p>🔴 {@code status} 는 {@code AVAILABLE | NO_DATA} 뿐이다 — 셀 자체의 데이터 유무만 담는다.
 * 개선도의 {@code NO_PREVIOUS_PERIOD | INSUFFICIENT_SAMPLE} 은 저장하지 않는다(설정이 바뀌면
 * 저장값이 거짓이 된다).</p>
 */
public record MonthlyWeaknessMetric(
	UUID teacherId,
	UUID studentId,
	String month,
	String monthZone,
	String areaTag,
	String typeTag,
	int scoredCount,
	int correctCount,
	String status,
	String calculationVersion,
	Instant calculatedAt
) {

	public static final String AVAILABLE = "AVAILABLE";
	public static final String NO_DATA = "NO_DATA";
}
