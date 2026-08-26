package com.checkon.member.learning.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/**
 * 배정 학습지 목록의 한 행. 계약({@code WorksheetSummary})과 필드가 일치한다.
 *
 * <p>🔴 {@code title} 은 파생값이다. {@code problem_assignments} 에 title 컬럼이 없어(전수 #5)
 * {@code WorksheetTitles.derive(itemCount, publishedAt)} 로 만든다 — 파생 규칙 정본은
 * {@code WorksheetTitles} 한 곳에 있다.</p>
 *
 * <p>🔴 {@code areaTag} · {@code estimatedMinutes} 는 원본이 학생에게 보이지 않아 <b>항상 null</b>
 * 이다. 0 이나 추정치로 채우지 마라 — 없는 값을 지어내지 않는다(절대 규칙 5).</p>
 *
 * <p>{@code status} 는 자기 attempt 유무로 계산한다 —
 * 없음 {@code NEW} · {@code IN_PROGRESS} 존재 → {@code IN_PROGRESS} · {@code SCORED} 존재 →
 * {@code COMPLETED}.</p>
 */
public record WorksheetSummaryResponse(
	UUID assignmentId,
	String title,
	String areaTag,
	int itemCount,
	Integer estimatedMinutes,
	String status,
	Instant publishedAt,
	TeacherSummaryView teacher,
	UUID latestAttemptId,
	Double accuracyRate
) {
}
