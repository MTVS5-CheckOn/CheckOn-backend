package com.checkon.member.analytics.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 월별 집계 결과를 자녀 홈의 지표 4종으로 옮긴다. <b>계산하지 않는다</b> — 이미 계산된 값을
 * 계약의 이름으로 다시 부를 뿐이다.
 *
 * <p>🔴 <b>여기서 새로 집계하지 않는 것이 요점이다.</b> 홈이 자기만의 산식을 가지면 같은 달의
 * 정확도가 홈과 분석 화면에서 다르게 나온다 — 정본이 둘이 되면 반드시 갈린다.
 * 원본은 {@code ParentAnalysisService} 가 낸 값 하나다.</p>
 *
 * <p>🔴 스프링 애노테이션 없음(코드 규칙 G4). 순수 함수라 통합 테스트 없이 검증된다.</p>
 */
public final class ParentHomeMetrics {

	private ParentHomeMetrics() {
	}

	/**
	 * @param overallStatus       {@code AnalysisResponse.Overall.status}
	 * @param accuracyRate        0~1 비율. 없으면 {@code null}
	 * @param scoredCount         채점 문항 수. 없으면 {@code null}
	 * @param averageActiveSec    문항당 평균 활동 초. 없으면 {@code null}
	 * @param improvementStatus   1위 약점 셀의 개선도 상태. 셀이 없으면 {@code null}
	 * @param accuracyDeltaPp     개선도 퍼센트포인트. 없으면 {@code null}
	 */
	public static List<HomeMetric> of(
		String overallStatus,
		BigDecimal accuracyRate,
		Integer scoredCount,
		Integer averageActiveSec,
		String improvementStatus,
		BigDecimal accuracyDeltaPp
	) {
		return List.of(
			// 🔴 정확도는 overall 의 status 를 그대로 따른다. 표본이 부족하면 INSUFFICIENT 이고
			//    값은 null 이다 — 그 상태에서 비율을 내려보내면 「믿을 만한 값」이라는 거짓말이다.
			metric(HomeMetric.MONTHLY_ACCURACY, overallStatus, accuracyRate, HomeMetric.RATIO),
			// 🔴 표본이 부족해도 「몇 개 풀었는가」는 정확히 안다. overall.status 를 따라가지
			//    않고 값의 유무로 판정한다 — INSUFFICIENT 인 달에도 개수는 AVAILABLE 이다.
			countMetric(HomeMetric.SOLVED_COUNT, scoredCount, HomeMetric.COUNT),
			countMetric(HomeMetric.AVERAGE_DURATION_SEC, averageActiveSec, HomeMetric.SECONDS),
			deltaMetric(improvementStatus, accuracyDeltaPp));
	}

	private static HomeMetric metric(String key, String status, BigDecimal value, String unit) {
		if (HomeMetric.AVAILABLE.equals(status) && value != null) {
			return HomeMetric.available(key, value, unit);
		}
		return HomeMetric.absent(key, absentStatus(status));
	}

	private static HomeMetric countMetric(String key, Integer value, String unit) {
		if (value == null) {
			return HomeMetric.absent(key, HomeMetric.NO_DATA);
		}
		return HomeMetric.available(key, BigDecimal.valueOf(value), unit);
	}

	/**
	 * 🔴 개선도 상태는 어휘가 다르다 — {@code AVAILABLE}·{@code NO_PREVIOUS_PERIOD}·
	 * {@code INSUFFICIENT_SAMPLE}·{@code NO_DATA} 4종인데 계약의 {@code metrics[].status} 는
	 * {@code ValueStatus} 4종이다. 그대로 흘려보내면 계약에 없는 값이 나간다.
	 */
	private static HomeMetric deltaMetric(String improvementStatus, BigDecimal deltaPp) {
		if (improvementStatus == null) {
			return HomeMetric.absent(HomeMetric.WEAKNESS_DELTA_PP, HomeMetric.NO_DATA);
		}
		if (HomeMetric.AVAILABLE.equals(improvementStatus) && deltaPp != null) {
			return HomeMetric.available(
				HomeMetric.WEAKNESS_DELTA_PP, deltaPp, HomeMetric.PERCENTAGE_POINT);
		}
		return HomeMetric.absent(HomeMetric.WEAKNESS_DELTA_PP,
			absentStatus(improvementStatus));
	}

	/**
	 * 값이 없을 때 쓸 상태로 옮긴다.
	 *
	 * <p>🔴 {@code NO_PREVIOUS_PERIOD} 를 {@code INSUFFICIENT} 가 아니라 {@code NO_DATA} 로
	 * 옮긴다 — 표본이 적은 것이 아니라 <b>비교할 지난달이 아예 없다</b>는 뜻이기 때문이다.</p>
	 *
	 * <p>🔴 <b>{@code AVAILABLE} 도 {@code NO_DATA} 가 된다.</b> 이 메서드는 값이 없을 때만
	 * 불리므로 「가용한데 값이 없다」는 모순이다. 그대로 흘려보내면 {@code HomeMetric} 생성자가
	 * 던져 <b>홈 화면 전체가 500</b> 이 된다 — 지표 하나를 못 채운 것이 화면을 죽이면 안 된다.</p>
	 *
	 * <p>🔴 모르는 값도 {@code NO_DATA} 다. 계약에 없는 문자열을 내보내지 않는다.</p>
	 */
	private static String absentStatus(String status) {
		if (status == null) {
			return HomeMetric.NO_DATA;
		}
		return switch (status) {
			case "INSUFFICIENT", "INSUFFICIENT_SAMPLE" -> HomeMetric.INSUFFICIENT;
			case HomeMetric.NO_DATA -> HomeMetric.NO_DATA;
			default -> HomeMetric.NO_DATA;
		};
	}
}
