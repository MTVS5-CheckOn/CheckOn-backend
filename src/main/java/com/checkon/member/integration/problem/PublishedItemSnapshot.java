package com.checkon.member.integration.problem;

import java.util.List;
import java.util.UUID;

/**
 * 강사가 배포한 학습지 문항 스냅샷 한 건. 원본은 승우님의
 * {@code saved_problem_set_items.item_snapshot}(V17) 이다.
 *
 * <p>🔴 <b>정답 번호를 역산하지 않는다.</b> 2026-08-25 재측정으로 스냅샷에 {@code correctNo}·
 * {@code areaTag}·{@code typeTag}·{@code skillNodeId} 가 이미 들어 있음을 확인했다
 * (설계 정본 §1-4 ③④). 옛 설계의 문자열 매칭 fallback 은 폐기했다 —
 * 강사 화면(V17 정답 판정 정수 비교)과 학생 채점이 갈리는 것을 막는다.</p>
 *
 * <p>🔴 <b>넷 중 하나라도 null 이면 attempt 를 시작하지 않는다.</b>
 * {@code problem_assignment_responses} 의 대응 컬럼이 전부 NOT NULL 이라
 * 제출 자체가 불가능하다. {@code PublishedWorksheetAdapter} 가 시작 시점에 판정하고
 * {@code WORKSHEET_NOT_GRADABLE}(422)를 낸다.</p>
 */
public record PublishedItemSnapshot(
	UUID itemId,
	int ordinal,
	String stem,
	String passage,
	Integer correctNo,
	String explanation,
	String areaTag,
	String typeTag,
	String skillNodeId,
	List<PublishedItemOption> options
) {
}
