package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

class DashboardOpenApiSecurityContractTest {
	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace"
	);
	private static final Set<String> PUBLIC_OPERATIONS = Set.of(
		"POST /auth/sign-up/teachers",
		"POST /auth/login"
	);

	@Test
	void everyOperationDocumentsItsActualAuthenticationBoundary() {
		Map<String, Object> document = loadDocument();

		assertThat(document.get("openapi")).isEqualTo("3.0.3");
		assertBearerRequired(document.get("security"), "top-level security");

		Map<String, Object> components = asMap(document.get("components"), "components");
		Map<String, Object> schemes = asMap(
			components.get("securitySchemes"), "components.securitySchemes"
		);
		Map<String, Object> bearer = asMap(
			schemes.get("bearerAuth"), "components.securitySchemes.bearerAuth"
		);
		assertThat(bearer)
			.containsEntry("type", "http")
			.containsEntry("scheme", "bearer")
			.containsEntry("bearerFormat", "JWT");
		Map<String, Object> refreshCookie = asMap(
			schemes.get("refreshCookie"), "components.securitySchemes.refreshCookie"
		);
		assertThat(refreshCookie)
			.containsEntry("type", "apiKey")
			.containsEntry("in", "cookie")
			.containsEntry("name", "CHECKON_REFRESH");

		Map<String, Object> paths = asMap(document.get("paths"), "paths");
		List<String> operations = new ArrayList<>();
		paths.forEach((path, pathItemValue) -> {
			Map<String, Object> pathItem = asMap(pathItemValue, "path " + path);
			pathItem.forEach((method, operationValue) -> {
				String normalizedMethod = method.toLowerCase(Locale.ROOT);
				if (!HTTP_METHODS.contains(normalizedMethod)) {
					return;
				}

				String operationLabel = normalizedMethod.toUpperCase(Locale.ROOT) + " " + path;
				operations.add(operationLabel);
				Map<String, Object> operation = asMap(operationValue, operationLabel);
				Object effectiveSecurity = operation.containsKey("security")
					? operation.get("security")
					: document.get("security");
				if (PUBLIC_OPERATIONS.contains(operationLabel)) {
					assertThat(asList(effectiveSecurity, operationLabel))
						.as(operationLabel)
						.isEmpty();
				}
				else if ("POST /auth/refresh".equals(operationLabel)) {
					assertSecuritySchemeRequired(
						effectiveSecurity, "refreshCookie", operationLabel
					);
				}
				else {
					assertBearerRequired(effectiveSecurity, operationLabel);
				}
			});
		});

		assertThat(operations).as("documented HTTP operations").isNotEmpty();
	}

	private Map<String, Object> loadDocument() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource("openapi/dashboard-api.yaml"));
		factory.afterPropertiesSet();
		Map<String, Object> document = factory.getObject();
		assertThat(document).as("parsed OpenAPI document").isNotNull();
		return document;
	}

	private void assertBearerRequired(Object securityValue, String label) {
		assertSecuritySchemeRequired(securityValue, "bearerAuth", label);
	}

	private void assertSecuritySchemeRequired(
		Object securityValue,
		String scheme,
		String label
	) {
		List<Object> alternatives = asList(securityValue, label);
		assertThat(alternatives).as(label).isNotEmpty();

		for (int index = 0; index < alternatives.size(); index++) {
			String alternativeLabel = label + " alternative " + index;
			Map<String, Object> requirement = asMap(
				alternatives.get(index), alternativeLabel
			);
			assertThat(requirement)
				.as(alternativeLabel)
				.isNotEmpty()
				.containsKey(scheme);
			assertThat(asList(requirement.get(scheme), alternativeLabel + " scopes"))
				.as(alternativeLabel + " scopes")
				.isEmpty();
		}
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
