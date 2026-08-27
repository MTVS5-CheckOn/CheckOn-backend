package com.checkon.member.analytics.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 월 경계를 UTC {@link Instant} 창으로 계산한다. 순수 함수, I/O 없음.
 *
 * <p>🔴 {@link ZoneId} 는 인자로 받는다. 클래스 안에서 {@code ZoneId.of(...)} 를 부르면 설정
 * ({@code checkon.member.metrics.month-zone})이 우회된다.</p>
 *
 * @param from 포함 (inclusive)
 * @param to   미포함 (exclusive)
 */
public record MonthWindow(Instant from, Instant to) {

	public static MonthWindow of(YearMonth month, ZoneId zone) {
		if (month == null) {
			throw new IllegalArgumentException("month must not be null");
		}
		if (zone == null) {
			throw new IllegalArgumentException("zone must not be null");
		}
		Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
		Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
		return new MonthWindow(from, to);
	}

	/** 주어진 시각이 어느 월에 속하는지 zone 을 적용해 판정한다. */
	public static YearMonth resolveMonth(Instant instant, ZoneId zone) {
		if (instant == null) {
			throw new IllegalArgumentException("instant must not be null");
		}
		if (zone == null) {
			throw new IllegalArgumentException("zone must not be null");
		}
		return YearMonth.from(instant.atZone(zone));
	}
}
