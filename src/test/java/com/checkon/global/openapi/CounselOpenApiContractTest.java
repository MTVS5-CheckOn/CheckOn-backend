package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

@DisplayName("학부모 상담 OpenAPI 계약")
class CounselOpenApiContractTest {

	@Test
	@DisplayName("Given 상담 3엔드포인트가 있을 때 When 계약을 읽으면 Then 세 경로 모두 문서화되어 있다")
	void documentsAllThreeCounselOperations() {
		Map<String, Object> paths = asMap(loadDocument().get("paths"));
		assertThat(paths).containsKeys("/counsel/drafts", "/counsel/drafts/{jobId}", "/counsel/drafts/{jobId}/refine", "/counsel/drafts/{jobId}/sent");
		assertThat(paths).doesNotContainKey("/api/v1/counsel/drafts");
	}

	@Test
	@DisplayName("Given 계약의 실제 예시 키가 6자일 때 When Idempotency-Key 파라미터를 읽으면 Then 최소 길이를 8로 두지 않는다")
	void counselIdempotencyKeyHasNoEightCharacterFloor() {
		Map<String, Object> components = asMap(loadDocument().get("components"));
		Map<String, Object> parameter = asMap(asMap(components.get("parameters")).get("CounselIdempotencyKey"));
		assertThat(parameter).containsEntry("name", "Idempotency-Key").containsEntry("required", true);
		assertThat(asMap(parameter.get("schema"))).containsEntry("minLength", 1);
	}

	@Test
	@DisplayName("Given 계약이 동결한 enum이 있을 때 When 스키마를 읽으면 Then 표 그대로의 값만 담는다")
	void frozenEnumsMatchTheContractExactly() {
		Map<String, Object> schemas = asMap(asMap(loadDocument().get("components")).get("schemas"));

		assertThat(asList(asMap(schemas.get("CounselTopic")).get("enum")))
			.containsExactly("grade", "schedule", "counsel_request", "etc");
		assertThat(asList(asMap(schemas.get("CounselJobPhase")).get("enum")))
			.containsExactly("queued", "leased", "running", "paused", "succeeded", "failed", "cancelled");
		assertThat(asList(asMap(schemas.get("CounselDraftStatus")).get("enum")))
			.containsExactly("generated", "template_only", "rejected_insufficient", "llm_failed", "gate_exhausted");
		assertThat(asList(asMap(schemas.get("CounselBlockedReason")).get("enum")))
			.containsExactly(
				"evidence_missing", "comparison_exposure", "tone_violation", "pii_exposure",
				"out_of_scope", "answer_integrity", "banned_topic", "prompt_injection"
			);
	}

	@Test
	@DisplayName("Given 초안 생성 요청 스키마가 있을 때 When 필수 필드를 읽으면 Then AI 계약의 필수 필드를 그대로 요구한다")
	void createDraftRequestRequiresTheContractsMandatoryFields() {
		Map<String, Object> schemas = asMap(asMap(loadDocument().get("components")).get("schemas"));
		Map<String, Object> request = asMap(schemas.get("CounselCreateDraftRequest"));
		assertThat(asList(request.get("required"))).containsExactlyInAnyOrder(
			"studentId", "classId", "inquiryRef", "topic", "urgency", "receivedAt", "text", "periodLabel", "facts"
		);
	}

	@Test
	@DisplayName("Given classify/confirmations 경로가 있을 때 When 계약을 읽으면 Then Idempotency-Key를 요구하지 않는다")
	void classifyOperationsDoNotRequireAnIdempotencyKey() {
		Map<String, Object> paths = asMap(loadDocument().get("paths"));
		assertThat(paths).containsKeys("/counsel/inquiries/{inquiryRef}/classify", "/counsel/inquiries/{inquiryRef}/confirmation");

		Map<String, Object> classify = asMap(asMap(paths.get("/counsel/inquiries/{inquiryRef}/classify")).get("post"));
		List<Object> parameters = classify.get("parameters") == null ? List.of() : asList(classify.get("parameters"));
		assertThat(parameters).noneMatch(parameter -> "#/components/parameters/CounselIdempotencyKey".equals(asMap(parameter).get("$ref")));
	}

	@Test
	@DisplayName("Given classify가 동결한 enum이 있을 때 When 스키마를 읽으면 Then 표 그대로의 값만 담는다")
	void classifyEnumsMatchTheContractExactly() {
		Map<String, Object> schemas = asMap(asMap(loadDocument().get("components")).get("schemas"));

		assertThat(asList(asMap(schemas.get("InquirySentiment")).get("enum"))).containsExactly("normal", "complaint");
		assertThat(asList(asMap(schemas.get("ClassifyFallbackReason")).get("enum"))).containsExactly("parse_exhausted", "tripwire_blocked");
		assertThat(asList(asMap(schemas.get("ConfirmationAction")).get("enum"))).containsExactly("confirmed", "corrected");
	}

	private Map<String, Object> loadDocument() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource("openapi/dashboard-api.yaml"));
		factory.afterPropertiesSet();
		Map<String, Object> document = factory.getObject();
		assertThat(document).isNotNull();
		return document;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private List<Object> asList(Object value) {
		assertThat(value).isInstanceOf(List.class);
		return (List<Object>) value;
	}
}
