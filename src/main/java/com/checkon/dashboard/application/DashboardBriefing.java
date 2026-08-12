package com.checkon.dashboard.application;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardBriefing(
	LocalDate date, List<Alert> alerts, List<Todo> todos, List<Reminder> reminders
) {
	public record Alert(
		UUID alertId,
		UUID studentId,
		String studentName,
		String className,
		String ruleId,
		String signalType,
		String displayLabel,
		int rank,
		String brief,
		boolean briefFallback,
		String status,
		Instant createdAt,
		List<Evidence> evidence
	) {
	}

	public record Evidence(String recordId, String summary) {
	}

	public record Todo(
		UUID todoId, String kind, String text, String displayLabel, Instant createdAt,
		Ref ref, LocalDate dueDate, boolean done
	) {}
	public record Ref(UUID alertId, String screen) {}
	public record Reminder(
		UUID reminderId, UUID alertId, UUID studentId, String studentName,
		long interventionCount,
		LatestIntervention latestIntervention, java.time.Instant scheduledAt
	) {}
	public record LatestIntervention(
		UUID interventionId, String type, String summary, java.time.Instant createdAt
	) {}
}
