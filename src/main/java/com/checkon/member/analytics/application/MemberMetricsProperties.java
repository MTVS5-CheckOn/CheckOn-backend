package com.checkon.member.analytics.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 월별 집계·개선도 설정.
 *
 * <p>🔴 상수 하드코딩 금지. 산식 코드에 {@code 10}·{@code "Asia/Seoul"} 리터럴이 있으면 반려다.
 * 기본값은 이 한 파일에만 둔다({@code application*.yaml} 무접촉 · PR1).</p>
 *
 * @param minimumSampleSize      개선도 판정에 필요한 최소 문항 수. 설계 §10-2 「초기 권장 월 10문항」
 *                               ·MB-07 PROPOSED. 응답 {@code minimumSampleSize} 로 되돌린다.
 * @param monthZone              월 경계 zone. §10-2·§17-7·MB-07 PROPOSED. 저장 시
 *                               {@code member_monthly_*.month_zone} 컬럼에 기록해 나중에
 *                               「어느 행이 어느 기준으로 계산됐는지」 판별한다.
 * @param calculationVersion     산식 버전. 산식이 바뀌면 이 값을 올린다. 응답과 저장 컬럼에 그대로.
 * @param drainMaxPerRequest     요청당 outbox 소비 상한.
 * @param batchStudentLimit      배치 1회 학생 상한.
 * @param batchMonthLimit        배치 1회 월 상한.
 * @param weaknessDetailItemLimit {@code WeaknessDetail.recentItems} 상한.
 * @param trendMonths            {@code LearningRecordDetail.trend} 배열 길이. 계약(§3-3)이
 *                               개수를 규정하지 않으므로 설정으로 둔다 — 상수 하드코딩 금지 원칙에 맞춘다.
 *                               응답에는 되돌리지 않는다(프론트가 배열 길이로 안다).
 */
@ConfigurationProperties("checkon.member.metrics")
public record MemberMetricsProperties(
	Integer minimumSampleSize,
	String monthZone,
	String calculationVersion,
	Integer drainMaxPerRequest,
	Integer batchStudentLimit,
	Integer batchMonthLimit,
	Integer weaknessDetailItemLimit,
	Integer trendMonths
) {

	public MemberMetricsProperties {
		minimumSampleSize = minimumSampleSize == null ? 10 : minimumSampleSize;
		monthZone = monthZone == null ? "Asia/Seoul" : monthZone;
		calculationVersion = calculationVersion == null ? "mm-1" : calculationVersion;
		drainMaxPerRequest = drainMaxPerRequest == null ? 5 : drainMaxPerRequest;
		batchStudentLimit = batchStudentLimit == null ? 500 : batchStudentLimit;
		batchMonthLimit = batchMonthLimit == null ? 3 : batchMonthLimit;
		weaknessDetailItemLimit = weaknessDetailItemLimit == null ? 20 : weaknessDetailItemLimit;
		trendMonths = trendMonths == null ? 6 : trendMonths;

		if (minimumSampleSize < 1) {
			throw new IllegalArgumentException("minimum-sample-size must be at least 1");
		}
		if (drainMaxPerRequest < 1) {
			throw new IllegalArgumentException("drain-max-per-request must be at least 1");
		}
		if (batchStudentLimit < 1) {
			throw new IllegalArgumentException("batch-student-limit must be at least 1");
		}
		if (batchMonthLimit < 1) {
			throw new IllegalArgumentException("batch-month-limit must be at least 1");
		}
		if (weaknessDetailItemLimit < 1) {
			throw new IllegalArgumentException("weakness-detail-item-limit must be at least 1");
		}
		if (trendMonths < 1) {
			throw new IllegalArgumentException("trend-months must be at least 1");
		}
	}
}
