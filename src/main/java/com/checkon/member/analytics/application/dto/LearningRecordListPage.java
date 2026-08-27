package com.checkon.member.analytics.application.dto;

import java.util.List;

/**
 * 학습기록 목록 페이지. cursor 열림 구간 결과.
 */
public record LearningRecordListPage(
	List<LearningRecordResponse> items,
	String nextCursor,
	boolean hasNext
) {
}
