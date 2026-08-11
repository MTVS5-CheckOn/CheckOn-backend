package com.checkon.detection.application;

/**
 * Determines how the backend is allowed to populate the AI contract's
 * {@code consent} field before and after the dedicated consent feature exists.
 */
public enum AiDetectionConsentMode {

	/**
	 * Temporary product policy for the implementation, demonstration, and
	 * approved interim operation period. Every current snapshot is analyzable.
	 */
	PRE_CONSENT_ALLOW_ALL,

	/**
	 * Safe transition mode. Until a recorded-consent resolver is implemented,
	 * no student has a recorded grant and the snapshot contains no such student.
	 */
	REQUIRE_RECORDED_GRANT
}
