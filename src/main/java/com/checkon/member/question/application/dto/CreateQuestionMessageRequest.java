package com.checkon.member.question.application.dto;

/**
 * 추가 질문 요청. {@code content} 만 받는다.
 * {@code author_role} · {@code author_account_id} 는 서버가 subject 로 채운다.
 */
public record CreateQuestionMessageRequest(String content) {
}
