package com.checkon.publication.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 발행된 메시지 한 건.
 *
 * <p>🔴 {@code publishedAt} 이 {@code NOT NULL} 이라 <b>미발행 메시지는 존재할 수 없다.</b>
 * V44 가 그 성질로 「강사 미승인 AI 초안이 학부모에게 새는 것」을 막았다 — 그래서 이 record 에
 * 「초안인가」를 담는 필드가 없다. 담을 자리가 없으면 실수도 없다.</p>
 */
public record TeacherConsultationMessageRow(
	UUID messageId,
	String authorRole,
	String content,
	Instant publishedAt
) {
}
