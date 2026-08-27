package com.checkon.member.question.domain;

/**
 * {@code member_question_messages.author_role}. V41 CHECK + RLS 정책이 최종 보장한다.
 *
 * <p>🔴 학생 정책은 {@code author_role = 'STUDENT'} 를 WITH CHECK 에 넣는다 —
 * 학생 컨텍스트로 TEACHER 메시지를 INSERT 하려 하면 정책이 거절한다. 위조 방지.</p>
 */
public enum QuestionAuthorRole {
	STUDENT, TEACHER
}
