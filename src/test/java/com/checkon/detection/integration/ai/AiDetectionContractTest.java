package com.checkon.detection.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
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
		String serialized = objectMapper.writeValueAsString(request);
		assertThat(serialized)
			.doesNotContain("studentName")
			.doesNotContain("student_name")
			.doesNotContain("realName")
			.doesNotContain("real_name");
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
		assertThat(response.data().stats().r1ThresholdPp()).isEqualByComparingTo("12.50");
		assertThat(response.data().stats().r1ThresholdSource()).isEqualTo("pooled");
		assertThat(response.data().stats().r1PoolN()).isEqualTo(42);
		assertThat(response.data().signals()).allSatisfy(signal -> {
			assertThat(signal.score()).isBetween(0.0, 1.0);
			assertThat(signal.evidence()).isNotEmpty();
		});
		assertThat(response.data().signals())
			.filteredOn(signal -> signal.signalType().equals("acc_drop"))
			.singleElement().satisfies(signal -> {
				assertThat(signal.lifecycle()).isEqualTo("ongoing");
				assertThat(signal.metric()).isEqualTo("accuracy");
				assertThat(signal.observed()).isEqualByComparingTo("0.65");
				assertThat(signal.baseline()).isEqualByComparingTo("0.82");
				assertThat(signal.sampleSize()).isEqualTo(20);
				assertThat(signal.evidence()).singleElement().satisfies(evidence -> {
					assertThat(evidence.role()).isEqualTo("trigger");
					assertThat(evidence.observed()).isEqualByComparingTo("0.65");
					assertThat(evidence.sampleSize()).isEqualTo(20);
					assertThat(evidence.occurredOn()).hasToString("2026-07-20");
				});
			});
		assertThat(response.data().signals())
			.filteredOn(signal -> signal.signalType().equals("return_care"))
			.singleElement()
			.extracting(AiDetectionResponse.Signal::rank)
			.isEqualTo(4);
	}

	@Test
	@DisplayName("Given AI live response, When parsed, Then advisory and lifecycle are retained")
	void givenAiLiveResponse_whenParsed_thenRetainsAdvisoryAndLifecycle() throws Exception {
		AiDetectionResponse response = objectMapper.readValue("""
			{
			  "data":{"signals":[{
			    "signal_id":"signal-1", "student_ref":"st_01", "class_ref":"cl_01",
			    "rule_id":"R1", "signal_type":"acc_drop", "display_label":"정답률 하락",
			    "future_signal_field":"ignored-for-compatible-rollout",
			    "score":1.0, "rank":1, "advisory":true, "lifecycle":"follow_up",
			    "brief":{"text":"참고 신호", "gate_passed":true, "fallback_used":false},
			    "evidence":[{"source_table":"learning_event","record_id":"le_1","summary":"근거","role":"trigger",
			      "future_evidence_field":"ignored-for-compatible-rollout"}]
			  }],"stats":{"students_evaluated":1,"signals_raised":1,
			    "excluded_under_2w":0,"capped_out":0,"rules_skipped":[]}},
			  "error":null,
			  "meta":{"execution_id":"execution-1","versions":{"contract":"0.2"}}
			}
			""", AiDetectionResponse.class);

		assertThat(response.data().signals()).singleElement().satisfies(signal -> {
			assertThat(signal.advisory()).isTrue();
			assertThat(signal.lifecycle()).isEqualTo("follow_up");
			assertThat(signal.evidence()).singleElement().satisfies(evidence -> {
				assertThat(evidence.sourceTable()).isEqualTo("learning_event");
				assertThat(evidence.recordId()).isEqualTo("le_1");
			});
		});
		assertThat(response.data().stats().r1ThresholdPp()).isNull();
		assertThat(response.data().stats().r1ThresholdSource()).isNull();
		assertThat(response.data().stats().r1PoolN()).isNull();
		assertThat(response.data().signals()).singleElement().satisfies(signal -> {
			assertThat(signal.metric()).isNull();
			assertThat(signal.evidence()).singleElement().satisfies(evidence ->
				assertThat(evidence.role()).isEqualTo("trigger")
			);
		});
	}

	private <T> T readFixture(String path, Class<T> type) throws Exception {
		try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("fixture %s", path).isNotNull();
			return objectMapper.readValue(input, type);
		}
	}
}
