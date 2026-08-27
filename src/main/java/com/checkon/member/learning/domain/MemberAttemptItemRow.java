package com.checkon.member.learning.domain;

import java.util.UUID;

/**
 * {@code member_attempt_items} 한 행의 저장 형태. {@code options} 는 정준 JSON 문자열이다.
 *
 * <p>🔴 문항 스냅샷은 attempt 시작 시점에 <b>동결</b>된다 — 이 record 를 만든 뒤 UPDATE 하지
 * 않는다(V40 이 UPDATE 정책을 만들지 않아 정책 레벨에서도 막힌다). area/type/skill 은 스냅샷에서
 * null 인 경우가 존재하지 않아야 attempt 를 시작할 수 있다({@code WORKSHEET_NOT_GRADABLE}).</p>
 */
public record MemberAttemptItemRow(
	UUID attemptId,
	UUID itemId,
	int ordinal,
	String stem,
	String passage,
	String optionsJson,
	int correctNo,
	String explanation,
	String areaTag,
	String typeTag,
	String skillNodeId
) {
}
