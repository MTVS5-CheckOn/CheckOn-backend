package com.checkon.member.question.application.dto;

import java.util.UUID;

/**
 * 질문 생성 요청.
 *
 * <p>🔴 <b>{@code teacherId} 필드를 두지 않는다.</b> teacherId 는 assignment 에서 서버가
 * 결정한다 — 필드가 없으면 위조가 불가능하다(타입이 방어다 · PR5 §5 원칙과 동일).</p>
 *
 * <p>🔴 {@code itemId} 단독은 {@code 400 INVALID_REQUEST} 다. attempt 없이 문항만 지목하면
 * {@code saved_problem_set_items} 를 다시 봐야 하고, 그건 "복사 이후 조회는 member_* 만"
 * (PR5 §4) 을 깬다.</p>
 */
public record CreateQuestionRequest(
	UUID assignmentId,
	UUID attemptId,
	UUID itemId,
	String title,
	String content
) {
}
