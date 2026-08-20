package com.checkon.counsel.integration.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

import com.checkon.counsel.application.KafkaCounselDraftResultConsumer;

/** Receives adapter outcome events; unrecoverable processing failures go to topic.dlt. */
@Component
@ConditionalOnProperty(prefix = "checkon.kafka.counsel-draft", name = "enabled", havingValue = "true")
public class KafkaCounselDraftResultListener {

	private final KafkaCounselDraftResultConsumer consumer;

	public KafkaCounselDraftResultListener(KafkaCounselDraftResultConsumer consumer) {
		this.consumer = consumer;
	}

	@RetryableTopic(attempts = "3", backOff = @BackOff(delay = 1_000, multiplier = 2.0), dltTopicSuffix = ".dlt")
	@KafkaListener(
		topics = "${checkon.kafka.counsel-draft.completed-topic}",
		groupId = "${checkon.kafka.counsel-draft.consumer-group-id}"
	)
	public void completed(ConsumerRecord<String, String> record) {
		consumer.consumeCompleted(record.topic(), record.value());
	}

	@RetryableTopic(attempts = "3", backOff = @BackOff(delay = 1_000, multiplier = 2.0), dltTopicSuffix = ".dlt")
	@KafkaListener(
		topics = "${checkon.kafka.counsel-draft.failed-topic}",
		groupId = "${checkon.kafka.counsel-draft.consumer-group-id}"
	)
	public void failed(ConsumerRecord<String, String> record) {
		consumer.consumeFailed(record.topic(), record.value());
	}
}
