package com.checkon.counsel.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
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
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;

@DisplayName("상담 초안 AI 클라이언트")
class HttpCounselClientTest {

	private static final String BASE_URL = "http://ai.example.test";

	private MockRestServiceServer server;
	private HttpCounselClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpCounselClient(builder.build(), "/v1/counsel/drafts");
	}

	@Nested
	@DisplayName("Given 초안 생성을 요청할 때")
	class GivenCreatingADraft {

		@Test
		@DisplayName("When AI가 정상 응답하면 Then 필수 헤더를 실어 보내고 202 바디를 파싱한다")
		void sendsRequiredHeadersAndParsesTheResponse() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts"))
				.andExpect(method(POST))
				.andExpect(header(HttpCounselClient.TENANT_ID_HEADER, "tn_demo_teacher"))
				.andExpect(header(HttpCounselClient.REQUEST_ID_HEADER, "req-1"))
				.andExpect(header(HttpCounselClient.IDEMPOTENCY_KEY_HEADER, "iq_884"))
				.andExpect(jsonPath("$.inquiry.topic").value("grade"))
				.andExpect(jsonPath("$.inquiry.urgency").value("immediate"))
				.andExpect(jsonPath("$.context.facts[0].record_id").value("le_2041"))
				.andRespond(withSuccess(readFixture("create-response-succeeded.json"), APPLICATION_JSON));

			var response = client.createDraft(sampleCreateRequest(),
				new CounselClient.RequestHeaders("tn_demo_teacher", "req-1", "iq_884"));

			assertThat(response.data().status()).isEqualTo(CounselJobPhase.SUCCEEDED);
			assertThat(response.data().jobId()).isEqualTo("019846dc-7c00-7000-8000-0000000006a1");
			assertThat(response.error()).isNull();
			assertThat(response.meta().versions().pipeline()).isEqualTo("0.1.0");
			server.verify();
		}

		@Test
		@DisplayName("When 같은 Idempotency-Key에 다른 바디가 이미 쓰였으면 Then 멱등 충돌 예외를 던진다")
		void mapsIdempotencyConflict() {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts"))
				.andExpect(method(POST))
				.andRespond(withStatus(CONFLICT).contentType(APPLICATION_JSON).body("""
					{"data":null,"error":{"code":"IDEMPOTENCY_CONFLICT","message":"conflict","detail":null},
					 "meta":{"execution_id":null,"versions":null}}
					"""));

			assertThatThrownBy(() -> client.createDraft(sampleCreateRequest(),
				new CounselClient.RequestHeaders("tn_demo_teacher", "req-2", "iq_884")))
				.isInstanceOf(CounselClientException.class)
				.satisfies(exception -> {
					var clientException = (CounselClientException) exception;
					assertThat(clientException.reason()).isEqualTo(CounselClientException.Reason.IDEMPOTENCY_CONFLICT);
					assertThat(clientException.httpStatus()).isEqualTo(409);
				});
			server.verify();
		}

		@Test
		@DisplayName("When AI 서버에 연결할 수 없으면 Then 네트워크 오류 예외를 던진다")
		void mapsNetworkFailure() {
			server.expect(once(), requestTo(BASE_URL + "/v1/counsel/drafts"))
				.andExpect(method(POST))
				.andRespond(withException(new IOException("connection refused")));

			assertThatThrownBy(() -> client.createDraft(sampleCreateRequest(),
				new CounselClient.RequestHeaders("tn_demo_teacher", "req-3", "iq_884")))
				.isInstanceOf(CounselClientException.class)
				.satisfies(exception -> {
					var clientException = (CounselClientException) exception;
					assertThat(clientException.reason()).isEqualTo(CounselClientException.Reason.NETWORK_ERROR);
					assertThat(clientException.httpStatus()).isNull();
				});
			server.verify();
		}
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

	private static CounselDraftCreateRequest sampleCreateRequest() {
		return new CounselDraftCreateRequest(
			new CounselDraftCreateRequest.Inquiry(
				"iq_884",
				CounselTopic.GRADE,
				CounselUrgency.IMMEDIATE,
				OffsetDateTime.parse("2026-07-31T14:20:00+09:00"),
				"요즘 아이가 힘들어하는 것 같은데 학원에서는 뭘 하고 있는 건가요?"
			),
			"st_8f2a",
			"pa_9c1d",
			"cl_a1",
			List.of("narrative", "anxious"),
			List.of(new CounselDraftCreateRequest.DismissedSuggestion("frequency", "monthly")),
			new CounselDraftCreateRequest.Context(
				"sha256:7d1e0000000000000000000000000000000000000000000000000000000000",
				"2026년 7월",
				List.of(
					new CounselDraftCreateRequest.Fact("le_2041", "6월 지문 42개·312문항"),
					new CounselDraftCreateRequest.Fact(null, "최근 4주 정답률 평균 81%")
				)
			)
		);
	}

	private String readFixture(String name) throws IOException {
		String path = "ai/counsel/" + name;
		try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
