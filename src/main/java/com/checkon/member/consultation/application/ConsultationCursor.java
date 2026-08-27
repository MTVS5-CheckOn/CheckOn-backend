package com.checkon.member.consultation.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record ConsultationCursor(Instant createdAt, UUID consultationId) {

	public static ConsultationCursor decode(String encoded) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			int separator = raw.indexOf(':');
			String timePart = raw.substring(0, separator);
			int dot = timePart.indexOf('.');
			long seconds = Long.parseLong(timePart.substring(0, dot));
			int nanos = Integer.parseInt(timePart.substring(dot + 1));
			UUID id = UUID.fromString(raw.substring(separator + 1));
			return new ConsultationCursor(Instant.ofEpochSecond(seconds, nanos), id);
		}
		catch (RuntimeException exception) {
			throw new InvalidConsultationCursorException(exception);
		}
	}

	public String encode() {
		String raw = createdAt.getEpochSecond() + "." + createdAt.getNano()
			+ ":" + consultationId;
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static final class InvalidConsultationCursorException extends RuntimeException {
		InvalidConsultationCursorException(Throwable cause) {
			super("consultation cursor is malformed", cause);
		}
	}
}
