package com.checkon.member.integration.problem;

/**
 * 스냅샷 문항의 선택지 한 개.
 *
 * <p>{@code misconceptionTag} 는 오답 선택지에서만 채워진다 — 정답 선택지는 null 이다.
 * 제출 트랜잭션이 {@code problem_assignment_responses.misconception_tag} 를 채울 때
 * 여기서 뽑아 쓴다({@code ck_problem_response_misconception} 이 오답인데 tag 가 null 이면 거절).</p>
 */
public record PublishedItemOption(
	int position,
	String content,
	String misconceptionTag
) {
}
