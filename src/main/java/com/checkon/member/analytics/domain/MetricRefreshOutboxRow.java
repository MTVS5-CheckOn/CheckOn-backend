package com.checkon.member.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_metric_refresh_outbox} 한 행. 학생이 자기 컨텍스트로 넣고 자기 컨텍스트로 소비한다
 * (학부모·강사 정책은 만들지 않는다).
 */
public record MetricRefreshOutboxRow(
	UUID id,
	UUID teacherId,
	UUID studentId,
	String month,
	String monthZone,
	String reason,
	String sourceRef,
	String status,
	int attemptCount,
	String lastErrorCode,
	Instant createdAt,
	Instant processedAt
) {

	public static final String PENDING = "PENDING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";

	public static final String REASON_ATTEMPT_SCORED = "ATTEMPT_SCORED";
}
