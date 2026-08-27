package com.checkon.publication.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 강사 상담 목록의 한 항목.
 *
 * <p>🔴 <b>목록에는 학부모 원문을 담지 않는다.</b> 목록은 「무엇에 답해야 하는가」를 고르는
 * 화면이고, 원문 전체는 상세에서 본다. 목록 응답이 로그·캐시·프록시에 남을 여지를 줄인다.</p>
 */
public record TeacherConsultationSummary(
	UUID consultationId,
	UUID studentId,
	String status,
	String topic,
	String urgency,
	Instant createdAt,
	Instant answeredAt,
	boolean answerable
) {
}
