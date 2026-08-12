package com.checkon.detection.integration.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

import com.checkon.detection.application.KafkaDetectionResultConsumer;

/** Receives AI outcome events; unrecoverable processing failures go to topic.dlt. */
@Component
@ConditionalOnProperty(
	prefix = "checkon.kafka.risk-detection",
	name = "enabled",
	havingValue = "true"
)
public class KafkaDetectionResultListener {

	private final KafkaDetectionResultConsumer consumer;

	public KafkaDetectionResultListener(KafkaDetectionResultConsumer consumer) {
		this.consumer = consumer;
	}

	@RetryableTopic(attempts = "3", backOff = @BackOff(delay = 1_000, multiplier = 2.0),
		dltTopicSuffix = ".dlt")
	@KafkaListener(
		topics = "${checkon.kafka.risk-detection.completed-topic}",
		groupId = "${checkon.kafka.risk-detection.consumer-group-id}"
	)
	public void completed(ConsumerRecord<String, String> record) {
		consumer.consumeCompleted(record.topic(), record.value());
	}

	@RetryableTopic(attempts = "3", backOff = @BackOff(delay = 1_000, multiplier = 2.0),
		dltTopicSuffix = ".dlt")
	@KafkaListener(
		topics = "${checkon.kafka.risk-detection.failed-topic}",
		groupId = "${checkon.kafka.risk-detection.consumer-group-id}"
	)
	public void failed(ConsumerRecord<String, String> record) {
		consumer.consumeFailed(record.topic(), record.value());
	}
}
