package com.checkon.problem.integration.kafka;

import org.springframework.stereotype.Service;

import com.checkon.problem.application.ProblemGenerationResultProcessor;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationTenantResolver;

@Service
public class ProblemGenerationResultEventHandler {
	private final ProblemGenerationResultEventParser parser;
	private final ProblemGenerationTenantResolver tenantResolver;
	private final ProblemGenerationResultProcessor processor;
	public ProblemGenerationResultEventHandler(ProblemGenerationResultEventParser parser,
		ProblemGenerationTenantResolver tenantResolver, ProblemGenerationResultProcessor processor) {
		this.parser = parser; this.tenantResolver = tenantResolver; this.processor = processor;
	}
	public void handle(String payload) {
		var event = parser.parse(payload);
		var teacherId = tenantResolver.resolve(event.requestId(), event.tenantAlias())
			.orElseThrow(() -> new ProblemGenerationEventContractException("problem request and tenant alias could not be resolved"));
		processor.process(teacherId, event);
	}
}
