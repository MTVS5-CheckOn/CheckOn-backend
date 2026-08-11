package com.checkon.detection.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.checkon.detection.integration.ai.AiDetectionConsentProperties;

/**
 * Resolves whether a student may be included in an AI detection snapshot.
 *
 * <p>The current system has no stored consent source. The explicitly approved
 * pre-consent mode therefore allows all students for the interim implementation
 * and demonstration period. When the frontend-to-backend consent feature is
 * added, {@link AiDetectionConsentMode#REQUIRE_RECORDED_GRANT} becomes the
 * transition mode and this policy is extended with the stored-status lookup.</p>
 */
@Component
public class AiDetectionConsentPolicy {

	private static final String GRANTED = "granted";
	private static final String UNKNOWN = "unknown";

	private final AiDetectionConsentProperties properties;

	public AiDetectionConsentPolicy(AiDetectionConsentProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	public Decision decide(UUID teacherId, UUID studentId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(studentId, "studentId must not be null");
		return switch (properties.mode()) {
			case PRE_CONSENT_ALLOW_ALL -> new Decision(true, GRANTED);
			// A consent source does not exist yet. Treat the absence of a recorded
			// grant as unknown and do not transmit the student's data.
			case REQUIRE_RECORDED_GRANT -> new Decision(false, UNKNOWN);
		};
	}

	public record Decision(boolean included, String requestConsent) {
	}
}
