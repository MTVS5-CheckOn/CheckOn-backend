package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
@DisplayName("문제 출제 스튜디오 OpenAPI 계약")
class ProblemStudioOpenApiContractTest {
	@Test
	@DisplayName("Given 프론트 4단계가 있을 때 When 계약을 읽으면 Then 단계별 경로와 문항 상태를 명시한다")
	void containsAllFrontendSteps() throws IOException {
		String yaml;
		try (InputStream input = getClass().getResourceAsStream("/openapi/dashboard-api.yaml")) {
			assertThat(input).isNotNull();
			yaml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		assertThat(yaml)
			.contains("/problem-studio/students:")
			.contains("/problem-studio/students/{studentId}/weakness-analysis:")
			.contains("/problem-studio/requests:")
			.contains("/problem-studio/requests/{requestId}/review:")
			.contains("/problem-studio/requests/{requestId}/selection:")
			.contains("/problem-studio/requests/{requestId}/save:")
			.contains("/problem-studio/requests/{requestId}/publish:")
			.contains("/problem-studio/requests/{requestId}/printable:")
			.contains("enum: [PASSED, REVIEW_REQUIRED, UNVERIFIABLE, EXCLUDED]")
			.contains("enum: [GOOD, WEAK_SIGNAL, WEAK_CONFIRMED, ON_HOLD]");
	}
}
