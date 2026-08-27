package com.checkon.member.consultation.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkon.member.consultation.domain.ConsultationAiStatus;
import com.checkon.member.consultation.domain.ConsultationStatus;

public record ConsultationDetailResponse(
	UUID consultationId,
	UUID studentId,
	UUID teacherId,
	ConsultationStatus status,
	Instant createdAt,
	Instant updatedAt,
	Instant answeredAt,
	ConsultationAiStatus aiAssistance,
	String content,
	List<ConsultationMessageResponse> messages
) {
}
