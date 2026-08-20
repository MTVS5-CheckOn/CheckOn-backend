package com.checkon.detection.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskDetectionKafkaAsyncApiContractTest {

	@Test
	@DisplayName("Given weekly activity contract, When reading AsyncAPI, Then enrolled seconds and omission semantics are explicit")
	void givenWeeklyActivityContract_whenReadingAsyncApi_thenIncludesEnrollmentSeconds()
		throws Exception {
		// Given
		String asyncApi = Files.readString(
			Path.of("docs/contracts/risk-detection-kafka.asyncapi.yaml")
		);

		// When / Then
		assertThat(asyncApi)
			.contains("activity_count, enrolled_seconds")
			.contains("enrolled_seconds: { type: integer, minimum: 1, maximum: 604800 }")
			.contains("A missing weekly_activity row means the week is not eligible for evaluation");
	}
}
