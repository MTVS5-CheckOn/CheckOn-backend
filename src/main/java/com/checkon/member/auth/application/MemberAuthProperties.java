package com.checkon.member.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * member 인증 계열의 정책값. 🔴 상한을 코드에 박지 않는다(코드 규칙 §2).
 *
 * @param publicIdMaxAttempts 공개 학생 ID 충돌 재시도 상한
 */
@ConfigurationProperties("checkon.member.auth")
public record MemberAuthProperties(Integer publicIdMaxAttempts) {

	public MemberAuthProperties {
		publicIdMaxAttempts = publicIdMaxAttempts == null ? 5 : publicIdMaxAttempts;
		if (publicIdMaxAttempts < 1) {
			throw new IllegalArgumentException("public-id-max-attempts must be at least 1");
		}
	}
}
