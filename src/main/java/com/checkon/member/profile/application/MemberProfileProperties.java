package com.checkon.member.profile.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알림 설정의 기본값. 상한·기본값은 코드에 박지 않는다(코드 규칙 §2).
 *
 * @param defaultEnabled 알림 preference 행이 없을 때 반환할 기본값. 기본 {@code true}.
 *                       🔴 행 부재를 {@code false} 로 읽지 마라.
 */
@ConfigurationProperties("checkon.member.notification")
public record MemberProfileProperties(Boolean defaultEnabled) {

	public MemberProfileProperties {
		defaultEnabled = defaultEnabled == null ? Boolean.TRUE : defaultEnabled;
	}
}
