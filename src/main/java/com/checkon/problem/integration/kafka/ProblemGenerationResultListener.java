package com.checkon.problem.integration.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "checkon.ai.problem-generation.kafka", name = "enabled", havingValue = "true")
public class ProblemGenerationResultListener {
	private final ProblemGenerationResultEventHandler handler;
	public ProblemGenerationResultListener(ProblemGenerationResultEventHandler handler) { this.handler = handler; }
	@KafkaListener(topics = "${checkon.ai.problem-generation.kafka.result-topic}",
		groupId = "${checkon.ai.problem-generation.kafka.consumer-group}",
		containerFactory = "problemGenerationKafkaListenerContainerFactory")
	public void consume(String payload) { handler.handle(payload); }
}
