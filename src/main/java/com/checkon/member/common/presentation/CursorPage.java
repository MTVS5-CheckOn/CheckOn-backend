package com.checkon.member.common.presentation;

import java.util.List;

/**
 * cursor pagination 응답. 결과가 0건이어도 오류가 아니며 빈 목록을 그대로 돌려준다.
 *
 * @param items      이번 페이지 항목
 * @param nextCursor 다음 페이지 커서. 마지막 페이지면 {@code null}
 * @param hasNext    다음 페이지 존재 여부
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {

	public static <T> CursorPage<T> lastPage(List<T> items) {
		return new CursorPage<>(items, null, false);
	}
}
