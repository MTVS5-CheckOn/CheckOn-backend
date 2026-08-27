package com.checkon.member.analytics.domain;

import java.util.Locale;
import java.util.Set;

/**
 * area·type 태그 정규화 — 순수 함수. 대소문자·공백 처리를 여기 한 곳에만 둔다.
 *
 * <p>🔴 백엔드 원본은 대문자 4종 (V17:34-36 CHECK), 정본은 소문자다(설계 §1-4 ④).
 * 변환은 여기 한 번뿐이고 API·저장은 전부 소문자.</p>
 *
 * <p>🔴 구버전 6영역을 5영역으로 매핑하는 표를 만들지 않는다 — 1:1 대응이 확인되지 않았다.
 * 매핑 불가한 값은 {@code null} 로 버린다.</p>
 */
public final class TagNormalizer {

	public static final Set<String> AREA_TAGS = Set.of(
		"language", "media", "literature", "reading", "speech_writing"
	);

	public static final Set<String> TYPE_TAGS = Set.of(
		"fact", "infer", "critic", "concept"
	);

	private TagNormalizer() {
	}

	/** area 정규화. 5개 정본 외 값은 {@code null} 로 버린다. */
	public static String normalizeArea(String raw) {
		String lowered = lower(raw);
		if (lowered == null) {
			return null;
		}
		return AREA_TAGS.contains(lowered) ? lowered : null;
	}

	/** type 정규화. 4개 정본 외 값은 {@code null} 로 버린다. */
	public static String normalizeType(String raw) {
		String lowered = lower(raw);
		if (lowered == null) {
			return null;
		}
		return TYPE_TAGS.contains(lowered) ? lowered : null;
	}

	private static String lower(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}
}
