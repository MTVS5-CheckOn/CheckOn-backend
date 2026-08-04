package com.checkon.dashboard.application;

import java.time.LocalDate;
import java.util.List;

public record DashboardCalendar(
	LocalDate startedAt,
	LocalDate endedAt,
	List<Item> items
) {
	public record Item(LocalDate date, long eventCount) {
	}
}
