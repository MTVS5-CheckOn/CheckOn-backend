package com.checkon.member.learning.application.dto;

import java.util.List;

/** 학생 홈. 진행 중/오늘 학습지가 없어도 각각 null/빈 배열로 정상 응답한다. */
public record StudentHomeResponse(
	String studentName,
	WorksheetSummaryResponse continuing,
	List<WorksheetSummaryResponse> todayWorksheets,
	StudentWeakness weakness
) {

	/** PR7 집계 전에는 NO_DATA이며 값은 지어내지 않고 null로 둔다. */
	public record StudentWeakness(
		String status,
		String areaTag,
		String typeTag,
		Double accuracyRate
	) {
		public static StudentWeakness noData() {
			return new StudentWeakness("NO_DATA", null, null, null);
		}
	}
}
