package com.checkon.detection.integration.ai;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

public interface RiskDetectionClient {

	AiDetectionResponse detect(
		AiDetectionRequest request,
		AiDetectionRequestHeaders headers
	);

	/** Sends an already validated canonical request without DTO reserialization. */
	AiDetectionResponse detectRaw(
		String requestBody,
		AiDetectionRequestHeaders headers
	);
}
