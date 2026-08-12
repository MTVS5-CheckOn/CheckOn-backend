package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

@DisplayName("문제 출제 OpenAPI 계약")
class ProblemGenerationOpenApiContractTest {

	@Test
	@DisplayName("Given v1 출제 범위가 있을 때 When 계약을 읽으면 Then 멱등 키와 안전한 입력 상한을 명시한다")
	void documentsTheIdempotentV1RequestBoundary() {
		Map<String, Object> document = loadDocument();
		Map<String, Object> paths = asMap(document.get("paths"));
		assertThat(paths).containsKeys("/problem-requests", "/problem-requests/{requestId}");
		assertThat(paths).doesNotContainKey("/api/v1/problem-requests");

		Map<String, Object> post = asMap(asMap(paths.get("/problem-requests")).get("post"));
		List<Object> parameters = asList(post.get("parameters"));
		assertThat(parameters).anySatisfy(parameter -> assertThat(asMap(parameter))
			.containsEntry("$ref", "#/components/parameters/IdempotencyKey"));

		Map<String, Object> components = asMap(document.get("components"));
		Map<String, Object> idempotencyKey = asMap(asMap(components.get("parameters")).get("IdempotencyKey"));
		assertThat(idempotencyKey)
			.containsEntry("name", "Idempotency-Key")
			.containsEntry("in", "header")
			.containsEntry("required", true);

		Map<String, Object> schemas = asMap(components.get("schemas"));
		Map<String, Object> request = asMap(schemas.get("ProblemGenerationRequest"));
		Map<String, Object> requestProperties = asMap(request.get("properties"));
		assertThat(asMap(requestProperties.get("count")))
			.containsEntry("minimum", 1)
			.containsEntry("maximum", 10);
		assertThat(asMap(requestProperties.get("manualTargets")))
			.containsEntry("minItems", 1)
			.containsEntry("maxItems", 20)
			.containsEntry("uniqueItems", true);
		assertThat(asMap(requestProperties.get("typeTags")))
			.containsEntry("maxItems", 4)
			.containsEntry("uniqueItems", true);
	}

	@Test
	@DisplayName("Given 비동기 요청 상태가 있을 때 When 계약을 읽으면 Then 전달 실패와 원본 AI 결과 조회 형태를 명시한다")
	void documentsThePersistentAsyncResultBoundary() {
		Map<String, Object> schemas = asMap(asMap(loadDocument().get("components")).get("schemas"));
		Map<String, Object> status = asMap(schemas.get("ProblemGenerationStatus"));
		assertThat(asList(status.get("enum"))).containsExactly(
			"QUEUED", "DISPATCHED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "DELIVERY_FAILED"
		);

		Map<String, Object> response = asMap(schemas.get("ProblemGenerationResponse"));
		Map<String, Object> properties = asMap(response.get("properties"));
		assertThat(properties).containsKeys(
			"requestId", "targetKind", "status", "result", "versions",
			"requestedAt", "dispatchedAt", "completedAt", "replayed"
		);
		assertThat(asMap(properties.get("result")))
			.containsEntry("type", "object")
			.containsEntry("nullable", true)
			.containsEntry("additionalProperties", true);
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
