package com.checkon.member.learning.application.dto;

import java.util.List;

/**
 * 학습지 상세. 🔴 <b>문항 본문·정답·해설을 포함하지 않는다</b>(계약 주석). 풀이는 attempt API 로 연다.
 *
 * <p>{@code itemBreakdown} 은 area/type 태그별 문항 수다. 🔴 원천 태그가 학생 컨텍스트에서
 * 보이지 않는 동안은 <b>빈 배열</b>을 낸다 — 계약이 요구하는 개별 항목의 {@code areaTag}·
 * {@code typeTag} 를 지어낼 수 없기 때문이다. "문항 태그 원천 부재" 는 open item 으로 등재됐다
 * (§2 상세 조회 항목).</p>
 */
public record WorksheetDetailResponse(
	WorksheetSummaryResponse summary,
	String description,
	List<ItemBreakdownRow> itemBreakdown
) {

	/**
	 * area/type 태그별 문항 수 한 행. 계약 {@code WorksheetDetail.itemBreakdown[]} 스키마와 같다.
	 * 🔴 태그가 학생에게 보이지 않는 동안은 이 record 자체가 생성되지 않는다(빈 배열).
	 */
	public record ItemBreakdownRow(String areaTag, String typeTag, int count) {
	}
}
