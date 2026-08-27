package com.checkon.publication.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 강사 상담 상세. 🔴 <b>학부모 원문({@code content})을 포함한다</b> — 강사가 답을 쓰려면
 * 질문을 봐야 하고, 그것이 이 엔드포인트의 목적이다.
 *
 * <p>🔴 <b>AI 초안 필드가 없다.</b> 우리 스키마에 AI 초안 <b>본문</b>을 담는 컬럼이 없다
 * ({@code ai_status}·{@code ai_job_id} 뿐이고, 계약이 「AI raw 초안은 저장하지 않는다」로
 * 정했다). 없는 값을 담는 필드를 두면 프론트가 있는 줄 안다 — 그래서 타입에 자리가 없다.</p>
 *
 * @param aiAssistance {@code ai_status} 그대로. 🔴 <b>초안 본문이 아니라 상태</b>다
 */
public record TeacherConsultationDetail(
	UUID consultationId,
	UUID studentId,
	UUID parentId,
	String status,
	String aiAssistance,
	String topic,
	String urgency,
	String content,
	Instant createdAt,
	Instant updatedAt,
	Instant answeredAt,
	boolean answerable,
	List<TeacherConsultationMessage> messages
) {
}
