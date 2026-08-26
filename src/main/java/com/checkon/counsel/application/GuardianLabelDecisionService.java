package com.checkon.counsel.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository.CurrentLabel;
import com.checkon.counsel.integration.ai.GuardianLabelClient;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest.Action;

@Service
public class GuardianLabelDecisionService {

	private static final Logger log = LoggerFactory.getLogger(GuardianLabelDecisionService.class);

	private final GuardianLabelDecisionTransactions transactions;
	private final GuardianLabelClient client;

	public GuardianLabelDecisionService(GuardianLabelDecisionTransactions transactions, GuardianLabelClient client) {
		this.transactions = transactions;
		this.client = client;
	}

	public List<CurrentLabel> currentLabels(UUID teacherId, UUID parentId) {
		return transactions.currentLabels(teacherId, parentId);
	}

	public GuardianLabelDecisionTransactions.StoredDecision decide(
		UUID teacherId, UUID parentId, UUID suggestionRef,
		Action action, GuardianLabelValue correctedValue
	) {
		var decision = transactions.decide(teacherId, parentId, suggestionRef, action, correctedValue);
		if (decision.newlyCreated()) notifyAiOnce(decision);
		return decision;
	}

	private void notifyAiOnce(GuardianLabelDecisionTransactions.StoredDecision decision) {
		try {
			client.confirm(request(decision), decision.tenantAlias(), UUID.randomUUID().toString());
		}
		catch (RuntimeException exception) {
			log.warn("Guardian label quality feedback delivery failed once: errorType={}",
				exception.getClass().getSimpleName());
		}
	}

	private static GuardianLabelConfirmationRequest request(
		GuardianLabelDecisionTransactions.StoredDecision decision
	) {
		return switch (decision.action()) {
			case confirmed -> GuardianLabelConfirmationRequest.confirmed(decision.suggestionId());
			case corrected -> GuardianLabelConfirmationRequest.corrected(decision.suggestionId(), decision.value());
			case rejected -> GuardianLabelConfirmationRequest.rejected(decision.suggestionId());
		};
	}
}
