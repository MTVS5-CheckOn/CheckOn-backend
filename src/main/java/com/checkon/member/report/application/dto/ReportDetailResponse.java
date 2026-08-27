package com.checkon.member.report.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkon.member.report.application.dto.ReportSummaryResponse.TeacherRef;

/**
 * 계약 {@code ReportDetail} — {@code ReportSummary} + {@code snapshotVersion} + {@code sections}.
 *
 * <p>🔴 {@code allOf} 상속을 자바 record 로는 못 하므로 요약 필드를 펼쳐 적는다. 요약과
 * 값이 갈리지 않도록 {@link #of} 하나에서만 만든다.</p>
 */
public record ReportDetailResponse(
	UUID reportId,
	String reportMonth,
	int revision,
	String status,
	Instant publishedAt,
	TeacherRef teacher,
	boolean hasPdf,
	String snapshotVersion,
	List<ReportSectionResponse> sections
) {

	public static ReportDetailResponse of(
		ReportSummaryResponse summary, String snapshotVersion, List<ReportSectionResponse> sections
	) {
		return new ReportDetailResponse(
			summary.reportId(), summary.reportMonth(), summary.revision(), summary.status(),
			summary.publishedAt(), summary.teacher(), summary.hasPdf(),
			snapshotVersion, sections);
	}
}
