package com.checkon.counsel.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest;
import com.checkon.counsel.domain.GuardianLabelSuggestionKey;

@DisplayName("학부모 라벨 제안 AI 클라이언트")
class HttpGuardianLabelClientTest {

	private static final String BASE_URL = "http://ai.example.test";

	private MockRestServiceServer server;
	private HttpGuardianLabelClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpGuardianLabelClient(builder.build(), "/v1/labels/suggest", "/v1/confirmations");
	}

	@Nested
	@DisplayName("Given 강사가 라벨 제안을 판단할 때")
	class GivenTeacherDecision {

		@Test
		@DisplayName("When guardian_ref에 콜론이 있어도 Then 오른쪽 axis와 value를 보존해 확정한다")
		void confirmsAKeyWhoseGuardianRefContainsColons() {
			String suggestionId = "tenant:guardian:comm:data";
			server.expect(once(), requestTo(BASE_URL + "/v1/confirmations"))
				.andExpect(method(POST))
				.andExpect(jsonPath("$.kind").value("label"))
				.andExpect(jsonPath("$.suggestion_id").value(suggestionId))
				.andExpect(jsonPath("$.action").value("confirmed"))
				.andExpect(jsonPath("$.corrected_value").doesNotExist())
				.andRespond(withSuccess("{\"data\":{\"accepted\":true},\"error\":null,\"meta\":null}", APPLICATION_JSON));

			var response = client.confirm(
				GuardianLabelConfirmationRequest.confirmed(suggestionId), "tn_teacher", "req-confirm"
			);

			assertThat(response.data().accepted()).isTrue();
			assertThat(GuardianLabelSuggestionKey.parse(suggestionId).guardianRef()).isEqualTo("tenant:guardian");
			server.verify();
		}

		@Test
		@DisplayName("When 같은 축의 값으로 정정하면 Then corrected_value에는 value만 보낸다")
		void correctsWithinTheSuggestedAxis() {
			server.expect(once(), requestTo(BASE_URL + "/v1/confirmations"))
				.andExpect(jsonPath("$.action").value("corrected"))
				.andExpect(jsonPath("$.corrected_value.value").value("narrative"))
				.andRespond(withSuccess("{\"data\":{\"accepted\":true},\"error\":null,\"meta\":null}", APPLICATION_JSON));

			client.confirm(
				GuardianLabelConfirmationRequest.corrected("gd_1:comm:data", GuardianLabelValue.NARRATIVE),
				"tn_teacher", "req-correct"
			);
			server.verify();
		}

		@Test
		@DisplayName("When 다른 축의 값으로 정정하면 Then AI 호출 전에 거절한다")
		void rejectsAnAxisValueMismatchBeforeCallingAi() {
			assertThatThrownBy(() -> GuardianLabelConfirmationRequest.corrected(
				"gd_1:comm:data", GuardianLabelValue.ANXIOUS
			)).isInstanceOf(IllegalArgumentException.class);
			server.verify();
		}

		@Test
		@DisplayName("When 제안을 거절하면 Then corrected_value 없이 rejected를 보낸다")
		void rejectsTheSuggestion() {
			server.expect(once(), requestTo(BASE_URL + "/v1/confirmations"))
				.andExpect(jsonPath("$.action").value("rejected"))
				.andExpect(jsonPath("$.corrected_value").doesNotExist())
				.andRespond(withSuccess("{\"data\":{\"accepted\":true},\"error\":null,\"meta\":null}", APPLICATION_JSON));

			client.confirm(
				GuardianLabelConfirmationRequest.rejected("gd_1:frequency:monthly"),
				"tn_teacher", "req-reject"
			);
			server.verify();
		}
	}

	@Nested
	@DisplayName("Given 마스킹된 상담 이력 다섯 건이 있을 때")
	class GivenFiveMaskedHistoryRecords {

		@Test
		@DisplayName("When 라벨을 제안하면 Then 필수 헤더와 +09:00 이력을 보내고 중첩 라벨을 파싱한다")
		void sendsTheContractAndParsesNestedLabel() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/labels/suggest"))
				.andExpect(method(POST))
				.andExpect(header(HttpGuardianLabelClient.TENANT_ID_HEADER, "tn_teacher"))
				.andExpect(header(HttpGuardianLabelClient.REQUEST_ID_HEADER, "req-label-1"))
				.andExpect(jsonPath("$.guardian_ref").value("gd_11b0"))
				.andExpect(jsonPath("$.history.length()").value(5))
				.andExpect(jsonPath("$.history[0].direction").value("inbound"))
				.andExpect(jsonPath("$.history[0].at").value("2026-06-12T10:11:00+09:00"))
				.andRespond(withSuccess(readFixture("post_labels_suggest.200.json"), APPLICATION_JSON));

			var response = client.suggest(request(), "tn_teacher", "req-label-1");

			var suggestion = response.data().suggestions().getFirst();
			assertThat(suggestion.label().axis()).isEqualTo(GuardianLabelAxis.COMM);
			assertThat(suggestion.label().value()).isEqualTo(GuardianLabelValue.DATA);
			assertThat(suggestion.confidence()).isEqualByComparingTo(new BigDecimal("0.86"));
			assertThat(suggestion.evidenceQuotes().getFirst().recordId()).isEqualTo("cm_88");
			server.verify();
		}

		@Test
		@DisplayName("When 문지기에 걸린 이력이 섞인 실제 응답이면 Then 정상 응답과 같은 제안을 파싱한다")
		void parsesTheBlockedHistoryFixtureAsNormal() throws Exception {
			server.expect(once(), requestTo(BASE_URL + "/v1/labels/suggest"))
				.andRespond(withSuccess(readFixture("post_labels_suggest.200.with_blocked_history.json"), APPLICATION_JSON));

			var response = client.suggest(request(), "tn_teacher", "req-label-2");

			assertThat(response.data().suggestions()).hasSize(1);
			assertThat(response.error()).isNull();
			server.verify();
		}

		@Test
		@DisplayName("When 응답 근거가 요청 이력에 없으면 Then 외부 결과를 저장하기 전에 거절한다")
		void rejectsEvidenceThatDoesNotExistInTheRequest() {
			server.expect(once(), requestTo(BASE_URL + "/v1/labels/suggest"))
				.andRespond(withSuccess("""
					{"data":{"suggestions":[{"suggestion_id":"gd_11b0:comm:data","guardian_ref":"gd_11b0",
					"label":{"axis":"comm","value":"data"},"confidence":0.8,
					"evidence_quotes":[{"record_id":"cm_missing","quote":"없는 인용"}]}]},"error":null,"meta":null}
					""", APPLICATION_JSON));

			assertThatThrownBy(() -> client.suggest(request(), "tn_teacher", "req-invalid-evidence"))
				.isInstanceOf(GuardianLabelClientException.class)
				.satisfies(exception -> assertThat(((GuardianLabelClientException) exception).reason())
					.isEqualTo(GuardianLabelClientException.Reason.INVALID_RESPONSE));
			server.verify();
		}

		@Test
		@DisplayName("When 같은 축이 두 번 오면 Then 계약 위반 응답으로 거절한다")
		void rejectsDuplicateAxes() {
			server.expect(once(), requestTo(BASE_URL + "/v1/labels/suggest"))
				.andRespond(withSuccess("""
					{"data":{"suggestions":[
					{"suggestion_id":"gd_11b0:comm:data","guardian_ref":"gd_11b0","label":{"axis":"comm","value":"data"},"confidence":0.8,"evidence_quotes":[{"record_id":"cm_88","quote":"숫자로"}]},
					{"suggestion_id":"gd_11b0:comm:narrative","guardian_ref":"gd_11b0","label":{"axis":"comm","value":"narrative"},"confidence":0.7,"evidence_quotes":[{"record_id":"cm_89","quote":"점수"}]}
					]},"error":null,"meta":null}
					""", APPLICATION_JSON));

			assertThatThrownBy(() -> client.suggest(request(), "tn_teacher", "req-duplicate-axis"))
				.isInstanceOf(GuardianLabelClientException.class)
				.satisfies(exception -> assertThat(((GuardianLabelClientException) exception).reason())
					.isEqualTo(GuardianLabelClientException.Reason.INVALID_RESPONSE));
			server.verify();
		}
	}

	@Nested
	@DisplayName("Given AI가 오류를 반환할 때")
	class GivenAiFailure {

		@Test
		@DisplayName("When 요청 스키마가 잘못되면 Then 400을 INVALID_REQUEST로 구분한다")
		void mapsInvalidSchema() throws Exception {
			assertReason(BAD_REQUEST, readFixture("post_labels_suggest.400.body_schema.json"),
				GuardianLabelClientException.Reason.INVALID_REQUEST);
		}

		@Test
		@DisplayName("When 계산을 못 하면 Then 재시도하지 않을 500으로 구분한다")
		void mapsInternalError() {
			assertReason(INTERNAL_SERVER_ERROR, "{}", GuardianLabelClientException.Reason.INTERNAL_ERROR);
		}

		@Test
		@DisplayName("When LLM 상류가 장애면 Then 503으로 구분하되 클라이언트가 재시도하지 않는다")
		void mapsUpstreamDownWithoutRetry() throws Exception {
			assertReason(SERVICE_UNAVAILABLE, readFixture("post_labels_suggest.503.upstream_down.json"),
				GuardianLabelClientException.Reason.UPSTREAM_DOWN);
		}

		@Test
		@DisplayName("When AI가 LLM 호출을 먼저 끊으면 Then 504로 구분한다")
		void mapsTimeout() {
			assertReason(GATEWAY_TIMEOUT, "{}", GuardianLabelClientException.Reason.TIMEOUT);
		}

		@Test
		@DisplayName("When 네트워크 연결이 실패하면 Then 한 번 호출 후 네트워크 오류를 노출한다")
		void mapsNetworkFailureWithoutRetry() {
			server.expect(once(), requestTo(BASE_URL + "/v1/labels/suggest"))
				.andRespond(withException(new IOException("connection refused")));

			assertThatThrownBy(() -> client.suggest(request(), "tn_teacher", "req-label-network"))
				.isInstanceOf(GuardianLabelClientException.class)
				.satisfies(exception -> assertThat(((GuardianLabelClientException) exception).reason())
					.isEqualTo(GuardianLabelClientException.Reason.NETWORK_ERROR));
			server.verify();
		}
	}

	private void assertReason(
		org.springframework.http.HttpStatus status,
		String body,
		GuardianLabelClientException.Reason expected
	) {
		server.expect(once(), requestTo(BASE_URL + "/v1/labels/suggest"))
			.andRespond(withStatus(status).contentType(APPLICATION_JSON).body(body));

		assertThatThrownBy(() -> client.suggest(request(), "tn_teacher", "req-label-error"))
			.isInstanceOf(GuardianLabelClientException.class)
			.satisfies(exception -> assertThat(((GuardianLabelClientException) exception).reason()).isEqualTo(expected));
		server.verify();
	}

	private GuardianLabelSuggestionRequest request() {
		return new GuardianLabelSuggestionRequest("gd_11b0", List.of(
			history("cm_88", "숫자로 정리해 주세요"),
			history("cm_89", "점수 추이 표로 부탁드려요"),
			history("cm_90", "지난주 결과가 궁금합니다"),
			history("cm_91", "표로 보여 주시면 좋겠어요"),
			history("cm_92", "이번 달 통계도 알려 주세요")
		));
	}

	private GuardianLabelSuggestionRequest.HistoryRecord history(String id, String text) {
		return new GuardianLabelSuggestionRequest.HistoryRecord(
			id,
			GuardianLabelSuggestionRequest.Direction.inbound,
			text,
			OffsetDateTime.parse("2026-06-12T10:11:00+09:00")
		);
	}

	private String readFixture(String name) throws IOException {
		String path = "ai/labels/" + name;
		try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
