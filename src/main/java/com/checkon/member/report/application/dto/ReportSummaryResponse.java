package com.checkon.member.report.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 계약 {@code ReportSummary}(member-api.yaml:2247).
 *
 * <p>🔴 {@code status} 를 {@link String} 상수로 고정한다. 계약의 enum 이 {@code PUBLISHED}
 * 하나뿐이라 <b>DRAFT 를 담을 수 있는 타입을 두지 않는다</b> — 값을 계산하지 않고 여기서 못
 * 박는다.</p>
 *
 * @param teacher 계약이 {@code required} 로 둔 {@code TeacherSummary}. 🔴 강사 이름을 못 읽으면
 *                {@code null} 이 아니라 이름만 비는 것이 아니라, 애초에 자녀의 활성 강사만
 *                보이므로 항상 채워진다
 */
public record ReportSummaryResponse(
	UUID reportId,
	String reportMonth,
	int revision,
	String status,
	Instant publishedAt,
	TeacherRef teacher,
	boolean hasPdf
) {

	public static final String PUBLISHED = "PUBLISHED";

	/** 계약 {@code TeacherSummary}. 🔴 {@code subject} 는 원본 정책이 없어 항상 {@code null} 이다. */
	public record TeacherRef(UUID teacherId, String displayName, String subject) {
	}
}
