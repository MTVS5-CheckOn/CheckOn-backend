package com.checkon.dashboard.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DashboardBriefing(LocalDate date, List<Alert> alerts) {
	public record Alert(
		UUID alertId,
		UUID studentId,
		String ruleId,
		String signalType,
		int rank,
		String brief,
		boolean briefFallback,
		String status,
		List<Evidence> evidence
	) {
	}

	public record Evidence(String recordId, String summary) {
	}
}
