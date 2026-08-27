package com.checkon.member.report.domain;

import java.util.UUID;

/** 발행 알림 대기열 한 행. {@code published_at} 이 NOT NULL 이라 미발행 행은 존재할 수 없다. */
public record ReportPublicationOutboxRow(
	UUID id,
	UUID reportId,
	UUID studentId,
	UUID teacherId,
	String reportMonth,
	int attemptCount
) {
}
