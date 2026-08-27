package com.checkon.publication.application.dto;

import java.time.Instant;
import java.util.UUID;

/** 발행된 메시지 한 건. 🔴 {@code publishedAt} 은 항상 값이 있다 — 미발행은 존재하지 않는다. */
public record TeacherConsultationMessage(
	UUID messageId,
	String authorRole,
	String content,
	Instant publishedAt
) {
}
