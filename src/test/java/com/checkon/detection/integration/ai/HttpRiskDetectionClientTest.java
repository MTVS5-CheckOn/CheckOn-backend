package com.checkon.detection.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.checkon.detection.application.DetectionExecutionKey;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class HttpRiskDetectionClientTest {

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.findAndAddModules()
		.build();

	private MockRestServiceServer server;
	private HttpRiskDetectionClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("http://ai.example.test");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpRiskDetectionClient(builder.build());
	}

	@Test
	void sendsRequiredHeadersAndReadsSuccessfulResponse() throws Exception {
		server.expect(once(), requestTo("http://ai.example.test/v1/detect"))
			.andExpect(method(POST))
			.andExpect(header(HttpRiskDetectionClient.TENANT_ID_HEADER, "tn_demo_teacher"))
			.andExpect(header(HttpRiskDetectionClient.REQUEST_ID_HEADER, "request-1"))
			.andExpect(header(
				HttpRiskDetectionClient.IDEMPOTENCY_KEY_HEADER,
				"tn_demo_teacher:2026-07-28"
			))
			.andRespond(withSuccess(
				readFixture("ai/detect-contract-response.json"),
				APPLICATION_JSON
			));

		var response = client.detect(
			readRequestFixture(),
			new AiDetectionRequestHeaders(
				"tn_demo_teacher",
				"request-1",
				DetectionExecutionKey.daily(
					"tn_demo_teacher",
					LocalDate.of(2026, 7, 28)
				)
			)
		);

		assertThat(response.error()).isNull();
		assertThat(response.data().signals()).hasSize(2);
		assertThat(response.meta().executionId()).isNotBlank();
		server.verify();
	}

	@Test
	void mapsIdempotencyConflict() throws Exception {
		server.expect(once(), requestTo("http://ai.example.test/v1/detect"))
			.andExpect(method(POST))
			.andRespond(withStatus(CONFLICT)
				.contentType(APPLICATION_JSON)
				.body("""
					{
					  "data": null,
					  "error": {
					    "code": "IDEMPOTENCY_CONFLICT",
					    "message": "same key with a different body",
					    "detail": null
					  },
					  "meta": {
					    "execution_id": "execution-1",
					    "versions": {
					      "contract": "0.1"
					    }
					  }
					}
					"""));

		assertThatThrownBy(() -> client.detect(
			readRequestFixture(),
			new AiDetectionRequestHeaders(
				"tn_demo_teacher",
				"request-2",
				DetectionExecutionKey.daily(
					"tn_demo_teacher",
					LocalDate.of(2026, 7, 28)
				)
			)
		))
			.isInstanceOf(RiskDetectionClientException.class)
			.satisfies(exception -> {
				var clientException = (RiskDetectionClientException)exception;
				assertThat(clientException.reason())
					.isEqualTo(RiskDetectionClientException.Reason.IDEMPOTENCY_CONFLICT);
				assertThat(clientException.httpStatus()).isEqualTo(409);
			});

		server.verify();
	}

	private AiDetectionRequest readRequestFixture() throws Exception {
		return objectMapper.readValue(
			readFixture("ai/detect-contract-request.json"),
			AiDetectionRequest.class
		);
	}

	private String readFixture(String path) throws IOException {
		try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
