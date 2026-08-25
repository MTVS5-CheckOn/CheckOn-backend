package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

class FrontendOpenApiReadinessContractTest {

	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
	private static final Set<String> PUBLIC_ENTRY_POINTS = Set.of(
		"POST /auth/sign-up/teachers",
		"POST /auth/login",
		"POST /auth/refresh"
	);

	@Test
	@DisplayName("Given 프론트 운영 계약일 때 When 모든 API를 검사하면 Then 식별·오류·비동기 조회 정보가 완전하다")
	void documentsFrontendReadyOperationMetadata() {
		Map<String, Object> document = loadDocument();
		Map<String, Object> paths = asMap(document.get("paths"), "paths");
		Set<String> operationIds = new HashSet<>();
		int[] operationCount = {0};

		paths.forEach((path, rawPathItem) -> asMap(rawPathItem, path).forEach((method, rawOperation) -> {
			String normalizedMethod = method.toLowerCase(Locale.ROOT);
			if (!HTTP_METHODS.contains(normalizedMethod)) {
				return;
			}
			operationCount[0]++;
			String label = normalizedMethod.toUpperCase(Locale.ROOT) + " " + path;
			Map<String, Object> operation = asMap(rawOperation, label);
			String operationId = (String) operation.get("operationId");
			assertThat(operationId).as(label + " operationId").isNotBlank();
			assertThat(operationIds.add(operationId)).as(label + " unique operationId").isTrue();
			assertThat((String) operation.get("summary")).as(label + " summary").isNotBlank();
			assertThat(asList(operation.get("tags"), label + " tags")).isNotEmpty();

			Map<String, Object> responses = asMap(operation.get("responses"), label + " responses");
			if (!PUBLIC_ENTRY_POINTS.contains(label)) {
				assertThat(responses).as(label + " auth errors").containsKeys("401", "403");
			}
			if (responses.containsKey("202")) {
				Map<String, Object> accepted = asMap(responses.get("202"), label + " 202");
				Map<String, Object> headers = asMap(accepted.get("headers"), label + " 202 headers");
				assertThat(headers).as(label + " polling location").containsKey("Location");
			}
		}));

		assertThat(operationCount[0]).isEqualTo(45);
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
	private Map<String, Object> asMap(Object value, String label) {
		assertThat(value).as(label).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private List<Object> asList(Object value, String label) {
		assertThat(value).as(label).isInstanceOf(List.class);
		return (List<Object>) value;
	}
}
