package com.checkon.global.config;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "checkon.dashboard.test-authentication")
public record DashboardTestAuthenticationProperties(
	boolean enabled,
	UUID teacherProfileId
) {
	public DashboardTestAuthenticationProperties {
		if (enabled && teacherProfileId == null) {
			throw new IllegalArgumentException(
				"checkon.dashboard.test-authentication.teacher-profile-id is required when enabled"
			);
		}
	}
}
