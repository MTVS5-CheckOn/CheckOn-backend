package com.checkon.dashboard.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DashboardCalendarServiceTest {
	private static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);
	private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 9);

	@Test
	void acceptsExactlyOneMondayThroughSundayWeek() {
		DashboardCalendarService.validateRange(MONDAY, SUNDAY);
	}

	@Test
	void rejectsStartAfterEnd() {
		assertThatThrownBy(() -> DashboardCalendarService.validateRange(
			MONDAY, MONDAY.minusDays(1)
		)).isInstanceOf(InvalidDashboardCalendarRangeException.class)
			.hasMessage("startedAt must not be after endedAt.");
	}

	@Test
	void rejectsRangesThatAreNotExactlyMondayThroughSunday() {
		assertThatThrownBy(() -> DashboardCalendarService.validateRange(
			MONDAY.plusDays(1), SUNDAY
		)).isInstanceOf(InvalidDashboardCalendarRangeException.class);
		assertThatThrownBy(() -> DashboardCalendarService.validateRange(
			MONDAY, SUNDAY.plusDays(7)
		)).isInstanceOf(InvalidDashboardCalendarRangeException.class);
	}
}
