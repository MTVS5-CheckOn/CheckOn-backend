package com.checkon.publication.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 답변 발행 결과.
 *
 * @param publishedAt 🔴 이 값이 있다는 것이 곧 <b>학부모에게 보인다</b>는 뜻이다.
 *                    {@code member_consultation_messages.published_at} 이 NOT NULL 이라
 *                    「저장은 됐는데 미발행」인 상태가 존재하지 않는다
 */
public record ConsultationAnswerResult(
	UUID consultationId,
	UUID messageId,
	String status,
	Instant publishedAt
) {
}
