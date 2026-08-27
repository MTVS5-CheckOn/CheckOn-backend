package com.checkon.member.learning.application;

import java.util.List;
import java.util.UUID;

/**
 * {@code WORKSHEET_NOT_GRADABLE}(422) 응답의 {@code details}. 계약 §「412 START」와
 * PR5 §2 「{@code details.itemIds[]}」의 계약이다.
 *
 * <p>🔴 {@code missing} 은 스냅샷에서 null 이 관측된 필드 이름을 열거한다 —
 * {@code correctNo}·{@code areaTag}·{@code typeTag}·{@code skillNodeId} 중 부분집합.
 * 로그·응답 어느 쪽에도 문항 본문·정답 텍스트는 실지 않는다(PR5 §3 로그 규칙).</p>
 */
public record WorksheetNotGradableDetails(List<UUID> itemIds, List<String> missing) {

	public WorksheetNotGradableDetails {
		itemIds = List.copyOf(itemIds);
		missing = List.copyOf(missing);
	}
}
