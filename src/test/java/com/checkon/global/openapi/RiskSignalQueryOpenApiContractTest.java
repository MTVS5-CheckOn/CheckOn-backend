package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

class RiskSignalQueryOpenApiContractTest {

	@Test
	@DisplayName("Given 위험신호 조회 계약, When OpenAPI를 읽으면, Then 실행 추적 필드를 모두 문서화한다")
	void givenRiskSignalQueryContract_whenReadingOpenApi_thenDocumentsRunProvenance() {
		Map<String, Object> schemas = schemas();

		assertThat(required(schemas, "AlertView")).contains("runId");
		assertThat(required(schemas, "DashboardAlert")).contains("runId");
		assertThat(required(schemas, "DetectionRunStatusResponse"))
			.contains("snapshotHash", "aiExecutionId");
	}

	@Test
	@DisplayName("Given 브리핑 근거 계약, When OpenAPI를 읽으면, Then 상세 근거와 같은 구조화 필드를 문서화한다")
	void givenDashboardEvidenceContract_whenReadingOpenApi_thenDocumentsStructuredEvidence() {
		assertThat(required(schemas(), "DashboardEvidence")).containsExactlyInAnyOrder(
			"id", "sourceHint", "recordId", "summary", "role",
			"observed", "sampleSize", "occurredOn"
		);
	}

	private Map<String, Object> schemas() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource("openapi/dashboard-api.yaml"));
		factory.afterPropertiesSet();
		Map<String, Object> document = asMap(factory.getObject(), "document");
		return asMap(asMap(document.get("components"), "components").get("schemas"), "schemas");
	}

	@SuppressWarnings("unchecked")
	private List<String> required(Map<String, Object> schemas, String schemaName) {
		Map<String, Object> schema = asMap(schemas.get(schemaName), schemaName);
		assertThat(schema.get("required")).as(schemaName + ".required").isInstanceOf(List.class);
		return (List<String>) schema.get("required");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object value, String label) {
		assertThat(value).as(label).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}
}
