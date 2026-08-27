package com.checkon.detection.integration.ai;

import java.util.Set;

/** Values accepted by the external risk-detection AI v1 contract. */
public final class AiDetectionSignalTypes {

	private static final Set<String> SUPPORTED = Set.of(
		"acc_drop",
		"submit_drop",
		"volume_gap",
		"hidden_risk",
		"return_care",
		"type_bias"
	);

	private AiDetectionSignalTypes() {
	}

	public static boolean supports(String signalType) {
		return signalType != null && SUPPORTED.contains(signalType);
	}
}
