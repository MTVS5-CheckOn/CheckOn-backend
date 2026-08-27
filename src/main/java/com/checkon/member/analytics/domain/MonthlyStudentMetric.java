package com.checkon.member.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code member_monthly_student_metrics} 한 행. */
public record MonthlyStudentMetric(
	UUID teacherId,
	UUID studentId,
	String month,
	String monthZone,
	int scoredCount,
	int correctCount,
	int totalActiveSec,
	String calculationVersion,
	Instant calculatedAt
) {
}
