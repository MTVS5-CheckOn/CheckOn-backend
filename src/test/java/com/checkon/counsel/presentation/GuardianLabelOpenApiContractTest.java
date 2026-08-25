package com.checkon.counsel.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

class GuardianLabelOpenApiContractTest {

	@Test
	@DisplayName("Given 학부모 라벨 화면 계약 When OpenAPI를 읽으면 Then 제안·현재값·판단과 명시적 eligibility 사유가 고정된다")
	void documentsTheGuardianLabelSurface() {
		Map<String, Object> document = load();
		Map<String, Object> paths = map(document.get("paths"));
		assertThat(paths).containsKeys(
			"/guardians/{parentId}/label-suggestions",
			"/guardians/{parentId}/labels",
			"/guardians/{parentId}/label-decisions"
		);

		Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
		assertThat(schemas).containsKeys(
			"GuardianLabelSuggestionResponse", "GuardianLabelsResponse", "GuardianLabelDecisionRequest"
		);
		assertThat(document.toString())
			.contains("INSUFFICIENT_HISTORY")
			.contains("ANALYSIS_UNAVAILABLE")
			.contains("suggestionRef")
			.contains("confirmed", "corrected", "rejected");
	}

	private Map<String, Object> load() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource("openapi/dashboard-api.yaml"));
		factory.afterPropertiesSet();
		return factory.getObject();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}
}
