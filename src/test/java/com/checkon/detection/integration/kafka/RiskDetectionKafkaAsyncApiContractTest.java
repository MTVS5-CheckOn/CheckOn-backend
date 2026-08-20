package com.checkon.detection.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskDetectionKafkaAsyncApiContractTest {

	@Test
	@DisplayName("Given snapshot hash contract, When reading AsyncAPI, Then canonical bytes are explicit")
	void givenSnapshotHashContract_whenReadingAsyncApi_thenCanonicalBytesAreExplicit()
		throws Exception {
		// Given
		String asyncApi = Files.readString(
			Path.of("docs/contracts/risk-detection-kafka.asyncapi.yaml")
		);

		// When / Then
		assertThat(asyncApi)
			.contains("sorts every object key lexicographically")
			.contains("render UTC as Z")
			.contains("exactly six fractional digits")
			.contains("Sub-microsecond precision is invalid")
			.contains("Unpaired UTF-16 surrogate code units are invalid")
			.contains("Nullable learning-event fields")
			.contains("only an empty detection_evidence key is omitted");
	}

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
