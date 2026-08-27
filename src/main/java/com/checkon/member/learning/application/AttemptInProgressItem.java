package com.checkon.member.learning.application;

import java.util.List;
import java.util.UUID;

/**
 * 진행 중 attempt 응답의 문항 한 개. 🔴 <b>정답 번호·해설·정오 필드를 담지 않는다</b>(절대 규칙 4).
 *
 * <p>결과용 필드가 필요하면 {@link AttemptItemResult} 를 쓴다 —
 * 같은 record 를 공유하거나 {@code @JsonInclude(NON_NULL)} 로 덮으려 하지 마라.
 * 타입 자체가 방어다: 반환 record 에 담을 자리가 없으므로 새어나갈 문법적 방법이 없다.</p>
 */
public record AttemptInProgressItem(
	UUID itemId,
	int ordinal,
	String stem,
	String passage,
	String areaTag,
	String typeTag,
	List<AttemptOption> options
) {
}
