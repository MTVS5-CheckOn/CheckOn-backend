package com.checkon.global.config;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "checkon.security.test-authentication")
public record DevelopmentTestAuthenticationProperties(
	boolean enabled,
	UUID accountId,
	UUID teacherProfileId
) {
	public DevelopmentTestAuthenticationProperties {
		if (enabled && (accountId == null || teacherProfileId == null)) {
			throw new IllegalArgumentException(
				"checkon.security.test-authentication account-id and teacher-profile-id are required when enabled"
			);
		}
	}
}
