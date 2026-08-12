package com.checkon.detection.integration.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

import com.checkon.detection.application.KafkaDetectionHttpAdapter;

/** Consumes Backend requests, calls the AI HTTP API, and emits Kafka outcomes. */
@Component
@ConditionalOnProperty(
	prefix = "checkon.kafka.risk-detection",
	name = "enabled",
	havingValue = "true"
)
public class KafkaDetectionHttpAdapterListener {

	private final KafkaDetectionHttpAdapter adapter;

	public KafkaDetectionHttpAdapterListener(KafkaDetectionHttpAdapter adapter) {
		this.adapter = adapter;
	}

	@RetryableTopic(
		attempts = "3",
		backOff = @BackOff(delay = 1_000, multiplier = 2.0),
		dltTopicSuffix = ".dlt"
	)
	@KafkaListener(
		topics = "${checkon.kafka.risk-detection.requested-topic}",
		groupId = "${checkon.kafka.risk-detection.requested-consumer-group-id}"
	)
	public void requested(ConsumerRecord<String, String> record) {
		adapter.handleRequested(record.key(), record.value());
	}

	@DltHandler
	public void exhausted(ConsumerRecord<String, String> record) {
		adapter.handleRetryExhausted(record.key(), record.value());
	}
}
