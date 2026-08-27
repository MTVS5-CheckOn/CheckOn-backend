package com.checkon.member.learning.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 학습지 표시 제목의 결정론 파생 규칙.
 *
 * <p>🔴 <b>원본 컬럼이 없어 파생한다.</b> {@code problem_assignments} 에는 title 컬럼이 없다
 * (전수 #5 · V17:147-163). 파생 규칙을 서비스마다 두면 값이 갈리므로 여기 한 곳에 고정한다.
 * <b>원본 컬럼이 생기면 이 클래스를 지운다</b> — 「지어낸 값」은 임시 조치임을 이름과 주석으로 남긴다.</p>
 *
 * <p>규칙 — {@code "학습지 %d문항 · %s"(itemCount, publishedAt 의 Asia/Seoul 날짜)}.
 * 시각대 원본은 설계 정본 §9 KST 규정을 따른다.</p>
 */
public final class WorksheetTitles {

	private static final DateTimeFormatter KST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final String KST_ZONE_ID = "Asia/Seoul";

	private WorksheetTitles() {
	}

	public static String derive(int itemCount, Instant publishedAt) {
		String dateText = KST_DATE.withZone(ZoneId.of(KST_ZONE_ID)).format(publishedAt);
		return "학습지 %d문항 · %s".formatted(itemCount, dateText);
	}
}
