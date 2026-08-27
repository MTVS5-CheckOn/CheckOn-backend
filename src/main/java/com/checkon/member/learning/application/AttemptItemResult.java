package com.checkon.member.learning.application;

import java.util.List;
import java.util.UUID;

/**
 * 채점 완료된 문항 한 개. 🔴 이 record 는 <b>결과 응답에만</b> 붙는다 —
 * 진행 중 응답은 {@link AttemptInProgressItem} 을 쓴다.
 *
 * <p>모든 문항에 대해 {@code correctNo}·{@code explanation}·{@code correct} 를 내려보낸다 —
 * 정답 문항의 접힌 해설도 열려야 하기 때문이다(§7 결과 반환 규칙).</p>
 */
public record AttemptItemResult(
	UUID itemId,
	int ordinal,
	Integer selectedNo,
	int correctNo,
	boolean correct,
	String stem,
	String passage,
	String explanation,
	String areaTag,
	String typeTag,
	List<AttemptOption> options
) {
}
