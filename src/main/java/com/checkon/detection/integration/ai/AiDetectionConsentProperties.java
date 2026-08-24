package com.checkon.detection.integration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.checkon.detection.application.AiDetectionConsentMode;

/**
 * Makes the temporary pre-consent policy explicit instead of leaving a
 * permanent-looking {@code "granted"} literal in snapshot construction.
 */
@ConfigurationProperties("checkon.ai.detection.consent")
public record AiDetectionConsentProperties(AiDetectionConsentMode mode) {

	public AiDetectionConsentProperties {
		if (mode == null) mode = AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL;
	}
}
