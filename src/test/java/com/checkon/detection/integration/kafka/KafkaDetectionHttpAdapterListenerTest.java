package com.checkon.detection.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.RetryableTopic;

class KafkaDetectionHttpAdapterListenerTest {

	@Test
	@DisplayName("Given 복구 불가능한 requested 오류, When retry 정책을 확인하면, Then IllegalArgumentException을 재시도에서 제외한다")
	void givenPermanentRequestedError_whenReadingRetryPolicy_thenExcludesIllegalArgumentException()
		throws NoSuchMethodException {
		RetryableTopic retry = KafkaDetectionHttpAdapterListener.class
			.getMethod("requested", ConsumerRecord.class)
			.getAnnotation(RetryableTopic.class);

		assertThat(retry).isNotNull();
		assertThat(retry.exclude()).containsExactly(IllegalArgumentException.class);
		assertThat(retry.attempts()).isEqualTo("3");
		assertThat(retry.dltTopicSuffix()).isEqualTo(".dlt");
	}
}
