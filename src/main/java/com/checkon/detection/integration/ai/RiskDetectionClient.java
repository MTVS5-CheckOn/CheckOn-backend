package com.checkon.detection.integration.ai;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

public interface RiskDetectionClient {

	AiDetectionResponse detect(
		AiDetectionRequest request,
		AiDetectionRequestHeaders headers
	);
}
