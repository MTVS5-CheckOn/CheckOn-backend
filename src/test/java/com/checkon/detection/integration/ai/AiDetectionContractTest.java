package com.checkon.detection.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiDetectionContractTest {

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.findAndAddModules()
		.build();

	@Test
	void requestFixtureMatchesAiContract() throws Exception {
		AiDetectionRequest request = readFixture(
			"ai/detect-contract-request.json",
			AiDetectionRequest.class
		);

		assertThat(request.snapshotMeta().weekStart().toString()).isEqualTo("2026-07-20");
		assertThat(request.snapshotMeta().classes()).hasSize(2);
		assertThat(request.students()).extracting(AiDetectionRequest.StudentSnapshot::studentRef)
			.containsExactly("st_10");
		assertThat(request.learningEvents()).hasSize(2);
		assertThat(request.learningEvents().get(1).correct()).isNull();
		assertThat(request.alertContext().getFirst().resolvedAt()).isNull();
	}

	@Test
	void responseFixtureMatchesAiContract() throws Exception {
		AiDetectionResponse response = readFixture(
			"ai/detect-contract-response.json",
			AiDetectionResponse.class
		);

		assertThat(response.error()).isNull();
		assertThat(response.meta().executionId()).isNotBlank();
		assertThat(response.meta().versions()).containsEntry("schema", "0.1");
		assertThat(response.data().stats().excludedUnderTwoWeeks()).isEqualTo(1);
		assertThat(response.data().signals()).allSatisfy(signal -> {
			assertThat(signal.score()).isBetween(0.0, 1.0);
			assertThat(signal.evidence()).isNotEmpty();
		});
		assertThat(response.data().signals())
			.filteredOn(signal -> signal.studentRef().equals("st_10"))
			.singleElement()
			.extracting(AiDetectionResponse.Signal::lifecycle)
			.isEqualTo("ongoing");
		assertThat(response.data().signals())
			.filteredOn(signal -> signal.signalType().equals("return_care"))
			.singleElement()
			.extracting(AiDetectionResponse.Signal::rank)
			.isEqualTo(4);
	}

	private <T> T readFixture(String path, Class<T> type) throws Exception {
		try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return objectMapper.readValue(input, type);
		}
	}
}
