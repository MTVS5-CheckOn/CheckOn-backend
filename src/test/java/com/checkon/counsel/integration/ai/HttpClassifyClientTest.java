package com.checkon.counsel.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.integration.ai.dto.ClassifyRequest;
import com.checkon.counsel.integration.ai.dto.ConfirmationRequest;

@DisplayName("문의 분류 AI 클라이언트")
class HttpClassifyClientTest {

	private static final String BASE_URL = "http://ai.example.test";

	private MockRestServiceServer server;
	private HttpClassifyClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpClassifyClient(builder.build(), "/v1/classify", "/v1/confirmations");
	}

	@Nested
	@DisplayName("Given 문의를 분류할 때")
	class GivenClassifyingAnInquiry {

		@Test
		@DisplayName("When AI가 분류에 성공하면 Then Idempotency-Key 없이 필수 헤더만 보내고 결과를 파싱한다")
		void sendsRequiredHeadersWithoutIdempotencyKeyAndParsesTheResponse() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/classify"))
				.andExpect(method(POST))
				.andExpect(header(HttpClassifyClient.TENANT_ID_HEADER, "tn_demo_teacher"))
				.andExpect(header(HttpClassifyClient.REQUEST_ID_HEADER, "req-1"))
				.andExpect(jsonPath("$.inquiry_ref").value("iq_204"))
				.andExpect(jsonPath("$.body_text").value("여름방학 특강 시간표가 궁금합니다"))
				.andRespond(withSuccess(readFixture("classify-response-classified.json"), APPLICATION_JSON));

			var response = client.classify(
				new ClassifyRequest("iq_204", "여름방학 특강 시간표가 궁금합니다"), "tn_demo_teacher", "req-1"
			);

			assertThat(response.data().topic()).isEqualTo(CounselTopic.SCHEDULE);
			assertThat(response.data().classified()).isTrue();
			assertThat(response.data().fallbackReason()).isNull();
			assertThat(response.data().confidence().topic().doubleValue()).isEqualTo(0.95);
			server.verify();
		}

		@Test
		@DisplayName("When 분류에 실패하면 Then classified:false와 고정 폴백 값을 200으로 그대로 파싱한다")
		void parsesAnUnclassifiedResultAsANormalResponse() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/classify"))
				.andExpect(method(POST))
				.andRespond(withSuccess(readFixture("classify-response-unclassified.json"), APPLICATION_JSON));

			var response = client.classify(
				new ClassifyRequest("iq_205", "..."), "tn_demo_teacher", "req-2"
			);

			assertThat(response.data().classified()).isFalse();
			assertThat(response.data().fallbackReason().name()).isEqualTo("TRIPWIRE_BLOCKED");
			assertThat(response.data().confidence().topic().doubleValue()).isEqualTo(0.0);
			server.verify();
		}

		@Test
		@DisplayName("When LLM 벤더 장애로 503이 오면 Then 재시도 가능한 예외로 매핑한다")
		void mapsUpstreamDown() {
			server.expect(once(), requestTo(BASE_URL + "/v1/classify"))
				.andExpect(method(POST))
				.andRespond(withStatus(SERVICE_UNAVAILABLE).contentType(APPLICATION_JSON).body("""
					{"data":null,"error":{"code":"LLM_UPSTREAM_DOWN","message":"vendor error","detail":null},"meta":null}
					"""));

			assertThatThrownBy(() -> client.classify(new ClassifyRequest("iq_206", "..."), "tn_demo_teacher", "req-3"))
				.isInstanceOf(ClassifyClientException.class)
				.satisfies(exception -> assertThat(((ClassifyClientException) exception).reason())
					.isEqualTo(ClassifyClientException.Reason.UPSTREAM_DOWN));
			server.verify();
		}

		@Test
		@DisplayName("When 우리 쪽 결함으로 500이 오면 Then 재시도해도 소용없는 예외로 구분해 매핑한다")
		void mapsInternalErrorSeparatelyFromRetryableFaults() {
			server.expect(once(), requestTo(BASE_URL + "/v1/classify"))
				.andExpect(method(POST))
				.andRespond(withStatus(INTERNAL_SERVER_ERROR).contentType(APPLICATION_JSON).body("""
					{"data":null,"error":{"code":"INTERNAL","message":"bug","detail":null},"meta":null}
					"""));

			assertThatThrownBy(() -> client.classify(new ClassifyRequest("iq_207", "..."), "tn_demo_teacher", "req-4"))
				.isInstanceOf(ClassifyClientException.class)
				.satisfies(exception -> assertThat(((ClassifyClientException) exception).reason())
					.isEqualTo(ClassifyClientException.Reason.INTERNAL_ERROR));
			server.verify();
		}

		@Test
		@DisplayName("When AI 서버에 연결할 수 없으면 Then 네트워크 오류 예외를 던진다")
		void mapsNetworkFailure() {
			server.expect(once(), requestTo(BASE_URL + "/v1/classify"))
				.andExpect(method(POST))
				.andRespond(withException(new IOException("connection refused")));

			assertThatThrownBy(() -> client.classify(new ClassifyRequest("iq_208", "..."), "tn_demo_teacher", "req-5"))
				.isInstanceOf(ClassifyClientException.class)
				.satisfies(exception -> assertThat(((ClassifyClientException) exception).reason())
					.isEqualTo(ClassifyClientException.Reason.NETWORK_ERROR));
			server.verify();
		}
	}

	@Nested
	@DisplayName("Given 분류를 확인·정정할 때")
	class GivenConfirmingAClassification {

		@Test
		@DisplayName("When 강사가 topic만 정정하면 Then corrected_value에 topic만 실어 보낸다")
		void sendsAPartialCorrection() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/confirmations"))
				.andExpect(method(POST))
				.andExpect(jsonPath("$.kind").value("classification"))
				.andExpect(jsonPath("$.suggestion_id").value("iq_204"))
				.andExpect(jsonPath("$.action").value("corrected"))
				.andExpect(jsonPath("$.corrected_value.topic").value("counsel_request"))
				.andExpect(jsonPath("$.corrected_value.sentiment").doesNotExist())
				.andRespond(withSuccess(readFixture("confirmation-response-accepted.json"), APPLICATION_JSON));

			var response = client.confirm(
				ConfirmationRequest.corrected("iq_204", CounselTopic.COUNSEL_REQUEST, null, null),
				"tn_demo_teacher", "req-6"
			);

			assertThat(response.data().accepted()).isTrue();
			server.verify();
		}

		@Test
		@DisplayName("When 대상 분류가 없으면 Then 404 예외를 던진다")
		void mapsNotFound() {
			server.expect(once(), requestTo(BASE_URL + "/v1/confirmations"))
				.andExpect(method(POST))
				.andRespond(withStatus(NOT_FOUND).contentType(APPLICATION_JSON).body("""
					{"data":null,"error":{"code":"NOT_FOUND","message":"no stored classification","detail":null},"meta":null}
					"""));

			assertThatThrownBy(() -> client.confirm(
				ConfirmationRequest.confirmed("iq_missing"), "tn_demo_teacher", "req-7"
			))
				.isInstanceOf(ClassifyClientException.class)
				.satisfies(exception -> assertThat(((ClassifyClientException) exception).reason())
					.isEqualTo(ClassifyClientException.Reason.NOT_FOUND));
			server.verify();
		}

		@Test
		@DisplayName("When 강사가 단순 확인만 하면 Then action=confirmed와 corrected_value 없이 보낸다")
		void sendsAPlainConfirmation() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/confirmations"))
				.andExpect(method(POST))
				.andExpect(jsonPath("$.action").value("confirmed"))
				.andExpect(jsonPath("$.corrected_value").doesNotExist())
				.andRespond(withSuccess(readFixture("confirmation-response-accepted.json"), APPLICATION_JSON));

			client.confirm(ConfirmationRequest.confirmed("iq_209"), "tn_demo_teacher", "req-8");
			server.verify();
		}
	}

	private String readFixture(String name) throws IOException {
		String path = "ai/classify/" + name;
		try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
