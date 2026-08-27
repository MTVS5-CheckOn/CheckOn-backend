package com.checkon.member.consultation.domain;

import java.time.Instant;
import java.util.UUID;

public record ConsultationRecord(
	UUID id,
	UUID parentId,
	UUID studentId,
	UUID teacherId,
	String content,
	String maskedContent,
	ConsultationStatus status,
	ConsultationAiStatus aiStatus,
	ConsultationContextType contextType,
	UUID contextId,
	String aiJobId,
	Instant createdAt,
	Instant updatedAt,
	Instant answeredAt,
	Instant cancelledAt
) {
}
