package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ClassManagementOpenApiContractTest {
	@Test
	void documentsClassPathsFieldsAndPagingWithoutDuplicatingTheServerPrefix() {
		Map<String, Object> document = loadDocument();
		Map<String, Object> paths = asMap(document.get("paths"));

		assertThat(paths).containsKeys(
			"/classes", "/classes/{classId}", "/classes/{classId}/archive"
		);
		assertThat(paths).doesNotContainKey("/api/v1/classes");
		assertThat(asMap(paths.get("/classes"))).containsKeys("get", "post");
		assertThat(asMap(paths.get("/classes/{classId}"))).containsKeys("get", "patch");
		assertThat(asMap(paths.get("/classes/{classId}/archive"))).containsKey("post");

		List<Object> servers = asList(document.get("servers"));
		assertThat(asMap(servers.getFirst())).containsEntry("url", "/api/v1");

		Map<String, Object> components = asMap(document.get("components"));
		Map<String, Object> schemas = asMap(components.get("schemas"));
		Map<String, Object> request = asMap(schemas.get("ClassDetailsRequest"));
		assertThat(asList(request.get("required")))
			.containsExactlyInAnyOrder("name", "subject");
		assertThat(asMap(asMap(request.get("properties")).get("memo")))
			.containsEntry("nullable", true)
			.containsEntry("maxLength", 1000);

		Map<String, Object> view = asMap(schemas.get("ClassView"));
		assertThat(asList(view.get("required"))).contains("subject", "memo");
		assertThat(asMap(asMap(view.get("properties")).get("subject")))
			.containsEntry("nullable", true)
			.containsEntry("maxLength", 100);

		Map<String, Object> parameters = asMap(components.get("parameters"));
		assertThat(asMap(asMap(parameters.get("Page")).get("schema")))
			.containsEntry("minimum", 0)
			.containsEntry("default", 0);
		assertThat(asMap(asMap(parameters.get("Size")).get("schema")))
			.containsEntry("minimum", 1)
			.containsEntry("maximum", 100)
			.containsEntry("default", 20);

		assertThat(asList(asMap(schemas.get("ClassPage")).get("required")))
			.containsExactlyInAnyOrderElementsOf(Set.of(
				"content", "page", "size", "totalElements", "totalPages"
			));
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
