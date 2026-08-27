package com.checkon.member.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 같은 (teacher_id, student_id, area_tag, type_tag) 셀의 이번 달·전월 정오 데이터를 놓고
 * 개선도를 판정한다. 순수 함수. I/O 없음. LLM 호출 0회.
 *
 * <p>🔴 「지난달 1위 약점」과 「이번 달 1위 약점」을 비교하지 않는다. 셀이 고정이고 달만 움직인다.</p>
 * <p>🔴 반올림한 비율끼리 빼지 않는다. 원본 count 로 비율을 만들고 마지막에 한 번만 반올림한다.</p>
 * <p>🔴 단위는 퍼센트포인트 (percentage points). 43% → 51% 는 {@code 8.0}.</p>
 * <p>🔴 {@link Status#AVAILABLE} 이 아닌 상태의 {@code accuracyDeltaPp} 는 반드시 {@code null}.
 * {@code 0.0} 으로 채우면 "변화 없음"이라는 거짓말이 된다.</p>
 */
public record WeaknessImprovement(
	Status status,
	BigDecimal accuracyDeltaPp,
	BigDecimal previousAccuracyRate,
	BigDecimal currentAccuracyRate,
	int minimumSampleSize
) {

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final int RATIO_SCALE = 10;

	public enum Status {
		AVAILABLE,
		NO_PREVIOUS_PERIOD,
		INSUFFICIENT_SAMPLE,
		NO_DATA
	}

	/**
	 * @param current 이번 달 셀. 셀 자체가 없으면 {@code null}.
	 * @param previous 전월 셀. 없으면 {@code null}.
	 * @param minimumSampleSize 최소 표본 (설정값).
	 */
	public static WeaknessImprovement of(Cell current, Cell previous, int minimumSampleSize) {
		if (minimumSampleSize < 1) {
			throw new IllegalArgumentException("minimumSampleSize must be at least 1");
		}
		// 1. current == null → NO_DATA
		if (current == null) {
			return new WeaknessImprovement(
				Status.NO_DATA, null, null, null, minimumSampleSize);
		}
		BigDecimal cur = ratio(current.correctCount(), current.scoredCount());
		// 2. previous == null → NO_PREVIOUS_PERIOD
		if (previous == null) {
			return new WeaknessImprovement(
				Status.NO_PREVIOUS_PERIOD, null, null, cur, minimumSampleSize);
		}
		BigDecimal prev = ratio(previous.correctCount(), previous.scoredCount());
		// 3. 표본 부족 (한쪽이라도)
		if (current.scoredCount() < minimumSampleSize
			|| previous.scoredCount() < minimumSampleSize) {
			return new WeaknessImprovement(
				Status.INSUFFICIENT_SAMPLE, null, prev, cur, minimumSampleSize);
		}
		// 4. AVAILABLE — 퍼센트포인트, 마지막에 1회 반올림.
		BigDecimal deltaPp = cur.subtract(prev).multiply(HUNDRED)
			.setScale(1, RoundingMode.HALF_UP);
		return new WeaknessImprovement(
			Status.AVAILABLE, deltaPp, prev, cur, minimumSampleSize);
	}

	private static BigDecimal ratio(int correct, int scored) {
		if (scored == 0) {
			return BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
		}
		return new BigDecimal(correct)
			.divide(new BigDecimal(scored), RATIO_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 개선도 계산에 쓰는 한 셀의 원본 카운트.
	 */
	public record Cell(int scoredCount, int correctCount) {
		public Cell {
			if (scoredCount < 0) {
				throw new IllegalArgumentException("scoredCount must be non-negative");
			}
			if (correctCount < 0 || correctCount > scoredCount) {
				throw new IllegalArgumentException(
					"correctCount must be between 0 and scoredCount");
			}
		}
	}
}
