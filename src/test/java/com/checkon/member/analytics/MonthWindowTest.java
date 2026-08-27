package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.checkon.member.analytics.domain.MonthWindow;

/**
 * 월 경계 zone 이 판정에 영향을 주는지 확인한다 (PR7 지시서 §5 테스트 11).
 */
final class MonthWindowTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final ZoneId UTC = ZoneOffset.UTC;

	@Test
	void zoneDecidesBoundary() {
		Instant border = Instant.parse("2026-07-31T15:00:00Z");
		// 🔴 두 zone 모두 단언한다.
		assertThat(MonthWindow.resolveMonth(border, KST)).isEqualTo(YearMonth.of(2026, 8));
		assertThat(MonthWindow.resolveMonth(border, UTC)).isEqualTo(YearMonth.of(2026, 7));
	}

	@Test
	void windowIsHalfOpen() {
		MonthWindow window = MonthWindow.of(YearMonth.of(2026, 8), KST);
		// 2026-08-01 00:00:00 KST = 2026-07-31 15:00:00 UTC
		assertThat(window.from()).isEqualTo(Instant.parse("2026-07-31T15:00:00Z"));
		// 2026-09-01 00:00:00 KST = 2026-08-31 15:00:00 UTC
		assertThat(window.to()).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));
	}
}
