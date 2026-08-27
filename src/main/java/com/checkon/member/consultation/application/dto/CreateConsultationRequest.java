package com.checkon.member.consultation.application.dto;

import java.util.UUID;

import com.checkon.member.consultation.domain.ConsultationContextType;

public record CreateConsultationRequest(
	UUID studentId,
	UUID teacherId,
	String content,
	ConsultationContext context
) {
	public record ConsultationContext(ConsultationContextType type, UUID id) {
	}
}
