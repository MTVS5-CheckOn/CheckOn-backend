package com.checkon.member.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 학습기록 상세의 월별 추이({@code LearningRecordDetail.trend}) 산식. 순수 함수, I/O 없음.
 *
 * <p>🔴 <b>실제 집계 결과만</b>이다 — 프론트가 쓰던 하드코딩(52/61/68)을 만들지 않는다.
 * 원천은 {@code member_monthly_student_metrics} 한 표뿐이다(계약 §3-3).</p>
 *
 * <p>🔴 <b>같은 (student_id, month) 에 여러 강사 행이 있으면 합산</b>한다 — 학생 총계는
 * 「teacher_id 가 다른 여러 행의 합」이다(계약 §3-3 · {@link MonthlyStudentMetric#teacherId()}
 * 는 셀 축이지 필터가 아니다).</p>
 *
 * <p>🔴 판정:
 * <ul>
 *   <li>{@code scored == 0} → 원소를 만들지 않는다 (0/0 을 「완전 오답」으로 오해할 여지가 있다).</li>
 *   <li>{@code 0 < scored < minimumSampleSize} → {@code INSUFFICIENT} · {@code accuracyRate:null}.
 *       {@code 0.0} 을 채우면 「변화 없음」이라는 거짓말이 된다.</li>
 *   <li>{@code scored >= minimumSampleSize} → {@code AVAILABLE} · 실제 비율.</li>
 * </ul>
 * </p>
 */
public final class LearningRecordTrend {

	private static final int RATIO_SCALE = 10;

	private LearningRecordTrend() {
	}

	/** trend 창을 만든다. anchor 월 포함해서 총 {@code monthCount} 개월. */
	public static MonthRange window(YearMonth anchor, int monthCount) {
		if (anchor == null) {
			throw new IllegalArgumentException("anchor must not be null");
		}
		if (monthCount < 1) {
			throw new IllegalArgumentException("monthCount must be at least 1");
		}
		YearMonth from = anchor.minusMonths(monthCount - 1L);
		return new MonthRange(from.toString(), anchor.toString());
	}

	/**
	 * 강사 행이 여러 개 있을 수 있으므로 월 단위로 count 를 합친 뒤 원소를 만든다.
	 *
	 * @return 시간순(오름차순) 리스트. 데이터가 하나도 없으면 빈 리스트.
	 */
	public static List<Point> compute(
		List<MonthlyStudentMetric> rows, int minimumSampleSize
	) {
		if (minimumSampleSize < 1) {
			throw new IllegalArgumentException("minimumSampleSize must be at least 1");
		}
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		TreeMap<String, int[]> agg = new TreeMap<>();
		for (MonthlyStudentMetric row : rows) {
			int[] bucket = agg.computeIfAbsent(row.month(), k -> new int[2]);
			bucket[0] += row.scoredCount();
			bucket[1] += row.correctCount();
		}
		List<Point> points = new ArrayList<>(agg.size());
		for (var entry : agg.entrySet()) {
			int scored = entry.getValue()[0];
			int correct = entry.getValue()[1];
			if (scored == 0) {
				continue;
			}
			if (scored < minimumSampleSize) {
				points.add(new Point(entry.getKey(), null, "INSUFFICIENT"));
				continue;
			}
			BigDecimal rate = new BigDecimal(correct).divide(
				new BigDecimal(scored), RATIO_SCALE, RoundingMode.HALF_UP);
			points.add(new Point(entry.getKey(), rate, "AVAILABLE"));
		}
		return points;
	}

	/** trend 조회 범위. 양끝 포함('YYYY-MM' 사전순 = 시간순). */
	public record MonthRange(String fromMonth, String toMonth) {
	}

	/** trend 배열의 한 원소. */
	public record Point(String month, BigDecimal accuracyRate, String status) {
	}
}
