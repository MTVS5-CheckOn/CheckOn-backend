package com.checkon.member.report.application.dto;

import java.util.List;

/** 계약 {@code CursorPage} + {@code items: ReportSummary[]}. */
public record ReportListPage(
	List<ReportSummaryResponse> items,
	String nextCursor,
	boolean hasNext
) {
}
