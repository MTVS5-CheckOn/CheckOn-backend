package com.checkon.member.learning.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 학습지 목록 커서.
 *
 * <p>포맷: {@code base64url("<epochSeconds>.<nanoOfSecond>:<assignmentId>")}.</p>
 *
 * <p>🔴 <b>epochMilli 를 쓰지 않는다.</b> PR5 지시서 초안이 epochMilli 를 제안했지만
 * PostgreSQL {@code TIMESTAMPTZ} 는 마이크로초까지 저장한다 — epochMilli 로 자르면
 * 같은 밀리초 안의 sub-millisecond 편차 때문에 {@code (published_at, id) < (cursor)} 이
 * 실제 데이터에서 tie 를 넘겨 다음 페이지가 <b>0건</b>이 된다(테스트로 관측). 초·나노초를
 * 둘 다 실어 정밀도 손실을 없앤다.</p>
 *
 * <p>🔴 형식이 어긋나면 <b>첫 페이지로 조용히 되돌리지 않는다</b> — 서비스가 이 예외를 잡아
 * {@code 400 INVALID_REQUEST} 로 번역한다(계약 §0-5). 조용히 첫 페이지를 내면 프론트가 무한
 * 스크롤에서 처음으로 튕기고, 그것이 서버 버그인지 사용자 실수인지 못 가린다.</p>
 *
 * <p>정렬 · 필터 정의는 {@link com.checkon.member.learning.infrastructure.persistence
 * .StudentWorksheetQueryRepository} 의 SQL 과 짝이다 — {@code published_at DESC, id DESC} 위에서
 * {@code (published_at, id) < (cursor.publishedAt, cursor.assignmentId)} 로 다음 페이지가 열린다.</p>
 */
public record WorksheetCursor(Instant publishedAt, UUID assignmentId) {

	public WorksheetCursor {
		Objects.requireNonNull(publishedAt, "publishedAt");
		Objects.requireNonNull(assignmentId, "assignmentId");
	}

	public static WorksheetCursor decode(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			throw new InvalidWorksheetCursorException("cursor is blank");
		}
		byte[] bytes;
		try {
			bytes = Base64.getUrlDecoder().decode(encoded);
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidWorksheetCursorException("cursor is not base64url", exception);
		}
		String raw = new String(bytes, StandardCharsets.UTF_8);
		int separator = raw.indexOf(':');
		if (separator <= 0 || separator == raw.length() - 1) {
			throw new InvalidWorksheetCursorException(
				"cursor missing '<epochSeconds>.<nanoOfSecond>:<uuid>'");
		}
		String timePart = raw.substring(0, separator);
		int dot = timePart.indexOf('.');
		if (dot <= 0 || dot == timePart.length() - 1) {
			throw new InvalidWorksheetCursorException(
				"cursor time part must be '<epochSeconds>.<nanoOfSecond>'");
		}
		Instant instant;
		UUID uuid;
		try {
			long seconds = Long.parseLong(timePart.substring(0, dot));
			int nanos = Integer.parseInt(timePart.substring(dot + 1));
			instant = Instant.ofEpochSecond(seconds, nanos);
			uuid = UUID.fromString(raw.substring(separator + 1));
		}
		catch (RuntimeException exception) {
			throw new InvalidWorksheetCursorException("cursor is malformed", exception);
		}
		return new WorksheetCursor(instant, uuid);
	}

	public String encode() {
		String raw = publishedAt.getEpochSecond() + "." + publishedAt.getNano()
			+ ":" + assignmentId;
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/** 🔴 커서 파싱 실패. 서비스가 잡아 400 으로 번역한다. */
	public static final class InvalidWorksheetCursorException extends RuntimeException {
		public InvalidWorksheetCursorException(String message) {
			super(message);
		}

		public InvalidWorksheetCursorException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
