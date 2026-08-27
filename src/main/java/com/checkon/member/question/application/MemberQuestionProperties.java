package com.checkon.member.question.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 질문 정책값. 상한을 코드에 박지 않는다(코드 규칙 §2).
 *
 * @param maxFollowUps 후속 질문 개수 상한. 기본 3.
 *                     🔴 팀 미확정 (설계 정본 §17 #9). 확정되면 이 기본값을 갱신하고
 *                     open item MB-46 를 닫는다.
 *                     🔴 초과는 조용히 자르지 않고 {@code 409} 로 거절한다(PR6 지시서 §3).
 */
@ConfigurationProperties("checkon.member.question")
public record MemberQuestionProperties(Integer maxFollowUps) {

	public MemberQuestionProperties {
		maxFollowUps = maxFollowUps == null ? 3 : maxFollowUps;
		if (maxFollowUps < 1) {
			throw new IllegalArgumentException("max-follow-ups must be at least 1");
		}
	}
}
