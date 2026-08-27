package com.checkon.member.question.domain;

/**
 * {@code member_questions.status} 전이. V41 CHECK 가 최종 보장한다.
 *
 * <pre>
 * WAITING     학생이 만든 초기 상태
 * ANSWERED    강사 답변 후. 🔴 이 PR 은 강사 답변 API 를 만들지 않으므로 공개 API 로는
 *             도달할 수 없다 (테스트는 리포지토리 레벨에서 상태를 만든다).
 * FOLLOW_UP   ANSWERED 뒤 학생 추가 질문. 상한은 Settings 값.
 * </pre>
 */
public enum QuestionStatus {
	WAITING, ANSWERED, FOLLOW_UP
}
