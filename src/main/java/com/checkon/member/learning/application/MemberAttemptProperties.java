package com.checkon.member.learning.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * attempt 정책값. 상한을 코드에 박지 않는다(코드 규칙 §2).
 *
 * @param maxProgressDeltaSeconds 항목당 progress delta 상한. 기본 600 (설계 정본 §17 #6).
 *                                🔴 초과는 조용히 clamp 하지 않고 400 INVALID_REQUEST 로 거절한다
 *                                (분기표 §1 progress 「시간 이상치」).
 */
@ConfigurationProperties("checkon.member.attempt")
public record MemberAttemptProperties(Integer maxProgressDeltaSeconds) {

	public MemberAttemptProperties {
		maxProgressDeltaSeconds =
			maxProgressDeltaSeconds == null ? 600 : maxProgressDeltaSeconds;
		if (maxProgressDeltaSeconds < 1) {
			throw new IllegalArgumentException(
				"max-progress-delta-seconds must be at least 1");
		}
	}
}
