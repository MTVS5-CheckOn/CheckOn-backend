package com.checkon.counsel.integration.ai;

import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionResponse;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest;
import com.checkon.counsel.integration.ai.dto.ConfirmationResponse;

public interface GuardianLabelClient {

	GuardianLabelSuggestionResponse suggest(
		GuardianLabelSuggestionRequest request,
		String tenantAlias,
		String requestId
	);

	ConfirmationResponse confirm(
		GuardianLabelConfirmationRequest request,
		String tenantAlias,
		String requestId
	);
}
