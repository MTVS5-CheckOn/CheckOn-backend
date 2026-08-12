package com.checkon.problem.integration.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository.OutboxMessage;

@Component
@ConditionalOnProperty(prefix = "checkon.ai.problem-generation.kafka", name = "enabled", havingValue = "true")
public class KafkaProblemGenerationEventPublisher implements ProblemGenerationEventPublisher {
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ProblemGenerationKafkaProperties properties;
	public KafkaProblemGenerationEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
		ProblemGenerationKafkaProperties properties) { this.kafkaTemplate = kafkaTemplate; this.properties = properties; }

	@Override
	public void publish(OutboxMessage message, Duration timeout) {
		ProducerRecord<String, String> record = new ProducerRecord<>(properties.requestTopic(), message.eventKey(), message.payload());
		addHeader(record, "event_id", message.id().toString());
		addHeader(record, "event_type", message.eventType());
		addHeader(record, "schema_version", message.schemaVersion());
		addHeader(record, "correlation_id", message.requestId().toString());
		addHeader(record, "tenant_id", message.eventKey());
		try { kafkaTemplate.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Kafka publish was interrupted", exception);
		}
		catch (Exception exception) { throw new IllegalStateException("Kafka publish failed", exception); }
	}
	private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
		record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
	}
}
