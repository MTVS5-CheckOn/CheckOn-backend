package com.checkon.detection.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaStringEncodingTest {

	@Test
	@DisplayName("Given 한글 Kafka payload, When 기본 String 직렬화 경계를 왕복하면, Then 모든 글자를 보존한다")
	void givenKoreanPayload_whenRoundTrippingKafkaStringSerde_thenPreservesEveryCharacter() {
		String payload = "{\"studentName\":\"API 테스트 학생\",\"className\":\"Kafka 테스트 반\"}";

		try (var serializer = new StringSerializer(); var deserializer = new StringDeserializer()) {
			byte[] serialized = serializer.serialize("checkon.encoding.test", payload);
			String deserialized = deserializer.deserialize("checkon.encoding.test", serialized);

			assertThat(deserialized).isEqualTo(payload);
		}
	}
}
