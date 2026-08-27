package com.checkon.dashboard.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DashboardCalendarServiceTest {
	private static final LocalDate STARTED_AT = LocalDate.of(2026, 8, 13);
	private static final LocalDate ENDED_AT = LocalDate.of(2026, 9, 10);

	@Test
	void acceptsFlexibleRangeAcrossWeekAndMonthBoundaries() {
		DashboardCalendarService.validateRange(STARTED_AT, ENDED_AT);
	}

	@Test
	void acceptsSameDateBecauseEndedAtIsInclusive() {
		DashboardCalendarService.validateRange(STARTED_AT, STARTED_AT);
	}

	@Test
	void rejectsStartAfterEnd() {
		assertThatThrownBy(() -> DashboardCalendarService.validateRange(
			STARTED_AT, STARTED_AT.minusDays(1)
		)).isInstanceOf(InvalidDashboardCalendarRangeException.class)
			.hasMessage("startedAt must not be after endedAt.");
	}
}
