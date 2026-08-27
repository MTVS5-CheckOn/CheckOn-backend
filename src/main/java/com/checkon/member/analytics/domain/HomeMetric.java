package com.checkon.member.analytics.domain;

import java.math.BigDecimal;

/**
 * 자녀 홈의 지표 한 개. 계약 {@code ParentHome.metrics[]}(member-api.yaml:2135-2144) 와 1:1.
 *
 * <p>🔴 {@code key} 는 계약의 <b>4종뿐</b>이다 — 어휘를 늘리지 않는다.
 * {@code status} 는 {@code ValueStatus} 4종({@code AVAILABLE}·{@code INSUFFICIENT}·
 * {@code NO_DATA}·{@code NOT_PRODUCED})뿐이다.</p>
 *
 * <p>🔴 <b>{@code AVAILABLE} 이 아니면 {@code value} 는 반드시 {@code null}</b> 이다.
 * 0 으로 채우면 「측정했더니 0이었다」는 거짓말이 된다 — 계약이 {@code status} 와 {@code value}
 * 를 함께 내려보내는 이유가 그 구분이다(설계 §7-1 「0과 결측을 구분」).</p>
 *
 * @param unit {@code AVAILABLE} 이 아니면 {@code null}. 값이 없는데 단위만 있는 것은 뜻이 없다
 */
public record HomeMetric(String key, String status, BigDecimal value, String unit) {

	public static final String MONTHLY_ACCURACY = "MONTHLY_ACCURACY";
	public static final String SOLVED_COUNT = "SOLVED_COUNT";
	public static final String AVERAGE_DURATION_SEC = "AVERAGE_DURATION_SEC";
	public static final String WEAKNESS_DELTA_PP = "WEAKNESS_DELTA_PP";

	public static final String RATIO = "RATIO";
	public static final String COUNT = "COUNT";
	public static final String SECONDS = "SECONDS";
	public static final String PERCENTAGE_POINT = "PERCENTAGE_POINT";

	public static final String AVAILABLE = "AVAILABLE";
	public static final String INSUFFICIENT = "INSUFFICIENT";
	public static final String NO_DATA = "NO_DATA";

	public HomeMetric {
		if (!AVAILABLE.equals(status) && (value != null || unit != null)) {
			throw new IllegalArgumentException(
				"non-AVAILABLE metric must carry neither value nor unit: " + key);
		}
		if (AVAILABLE.equals(status) && value == null) {
			throw new IllegalArgumentException("AVAILABLE metric must carry a value: " + key);
		}
	}

	/** 값이 있는 지표. */
	public static HomeMetric available(String key, BigDecimal value, String unit) {
		return new HomeMetric(key, AVAILABLE, value, unit);
	}

	/** 🔴 값이 없는 지표. 오류가 아니라 계약이 정한 정상 응답이다(분기표 §0 ⑧). */
	public static HomeMetric absent(String key, String status) {
		return new HomeMetric(key, status, null, null);
	}
}
