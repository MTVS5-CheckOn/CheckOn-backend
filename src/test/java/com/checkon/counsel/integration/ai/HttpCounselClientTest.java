package com.checkon.counsel.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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

import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;

@DisplayName("상담 초안 AI 클라이언트")
class HttpCounselClientTest {

	private static final String BASE_URL = "http://ai.example.test";

	private MockRestServiceServer server;
	private HttpCounselClient client;

	@BeforeEach
	void setUp() {
		// GET and refine now run on separate RestClients (different read
		// timeouts) -- one MockRestServiceServer-bound RestClient is reused
		// for both here since these tests don't exercise timeout behavior.
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		client = new HttpCounselClient(restClient, restClient, "/v1/counsel/drafts");
	}

	@Nested
	@DisplayName("Given 초안 결과를 조회할 때")
	class GivenFetchingADraft {

		@Test
		@DisplayName("When 잡이 성공했지만 근거 부족으로 초안이 없으면 Then 종단 상태와 정직한 거부 사유를 함께 반환한다")
		void parsesGeneratedDraft() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts/019846dc-7c00-7000-8000-0000000006a1"))
				.andExpect(method(GET))
				.andExpect(header(HttpCounselClient.TENANT_ID_HEADER, "tn_demo_teacher"))
				.andRespond(withSuccess(readFixture("get-response-generated.json"), APPLICATION_JSON));

			var response = client.getDraft("019846dc-7c00-7000-8000-0000000006a1", "tn_demo_teacher", null);

			assertThat(response.data().status()).isEqualTo(CounselJobPhase.SUCCEEDED);
			assertThat(response.data().result().draftStatus().name()).isEqualTo("GENERATED");
			assertThat(response.data().result().citations()).hasSize(2);
			server.verify();
		}

		@Test
		@DisplayName("When 잡은 성공했는데 본문이 사라졌으면 Then llm_failed와 draft_body_missing 사유를 그대로 전달한다")
		void parsesDraftBodyMissing() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts/019846dc-7c00-7000-8000-0000000006d4"))
				.andExpect(method(GET))
				.andRespond(withSuccess(readFixture("get-response-draft-body-missing.json"), APPLICATION_JSON));

			var response = client.getDraft("019846dc-7c00-7000-8000-0000000006d4", "tn_demo_teacher", "req-4");

			assertThat(response.data().result().draftStatus().name()).isEqualTo("LLM_FAILED");
			assertThat(response.data().result().statusReason()).isEqualTo("draft_body_missing");
			assertThat(response.data().result().text()).isNull();
			server.verify();
		}

		@Test
		@DisplayName("When 존재하지 않는 job_id를 조회하면 Then 404 예외를 던진다")
		void mapsNotFound() {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts/missing-job"))
				.andExpect(method(GET))
				.andRespond(withStatus(NOT_FOUND).contentType(APPLICATION_JSON).body("""
					{"data":null,"error":{"code":"NOT_FOUND","message":"job_id 부재","detail":null},
					 "meta":{"execution_id":null,"versions":null}}
					"""));

			assertThatThrownBy(() -> client.getDraft("missing-job", "tn_demo_teacher", null))
				.isInstanceOf(CounselClientException.class)
				.satisfies(exception -> {
					var clientException = (CounselClientException) exception;
					assertThat(clientException.reason()).isEqualTo(CounselClientException.Reason.NOT_FOUND);
					assertThat(clientException.httpStatus()).isEqualTo(404);
				});
			server.verify();
		}
	}

	@Nested
	@DisplayName("Given 초안을 다듬을 때")
	class GivenRefiningADraft {

		@Test
		@DisplayName("When Idempotency-Key 없이 요청 헤더를 구성해도 Then 클라이언트는 있는 그대로 전송한다")
		void sendsIdempotencyKeyAsRequired() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts/019846dc-7c00-7000-8000-0000000006a1/refine"))
				.andExpect(method(POST))
				.andExpect(header(HttpCounselClient.IDEMPOTENCY_KEY_HEADER, "turn-uuid-1"))
				.andExpect(jsonPath("$.instruction").value("조금 더 부드러운 말투로 바꿔 주세요."))
				.andExpect(jsonPath("$.turn_no").value(1))
				.andRespond(withSuccess(readFixture("refine-response-applied.json"), APPLICATION_JSON));

			var response = client.refineDraft(
				"019846dc-7c00-7000-8000-0000000006a1",
				new CounselDraftRefineRequest("조금 더 부드러운 말투로 바꿔 주세요.", 1),
				new CounselClient.RequestHeaders("tn_demo_teacher", null, "turn-uuid-1")
			);

			assertThat(response.data().applied()).isTrue();
			assertThat(response.data().blockedReason()).isNull();
			server.verify();
		}

		@Test
		@DisplayName("When 게이트가 지시를 차단하면 Then 200으로 applied:false와 차단 사유를 반환한다(예외 아님)")
		void parsesGateBlockAsANormalResponse() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts/019846dc-7c00-7000-8000-0000000006a1/refine"))
				.andExpect(method(POST))
				.andRespond(withSuccess(readFixture("refine-response-blocked.json"), APPLICATION_JSON));

			var response = client.refineDraft(
				"019846dc-7c00-7000-8000-0000000006a1",
				new CounselDraftRefineRequest("반 평균도 넣어 주세요.", 2),
				new CounselClient.RequestHeaders("tn_demo_teacher", "req-5", "turn-uuid-2")
			);

			assertThat(response.data().applied()).isFalse();
			assertThat(response.data().text()).isNull();
			assertThat(response.data().blockedReason().name()).isEqualTo("COMPARISON_EXPOSURE");
			server.verify();
		}
	}

	private String readFixture(String name) throws IOException {
		String path = "ai/counsel/" + name;
		try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
