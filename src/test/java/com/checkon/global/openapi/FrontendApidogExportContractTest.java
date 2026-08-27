package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

class FrontendApidogExportContractTest {

	private static final int EXPECTED_OPERATION_COUNT = 58;
	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
	private static final String REVISION_PATH =
		"/problem-studio/requests/{requestId}/executions/{executionId}/slots/{slotIndex}/revisions";
	private static final Set<String> REQUIRED_GUARDIAN_PATHS = Set.of(
		"/guardians", "/guardians/{parentId}/communications",
		"/guardians/{parentId}/label-suggestions", "/guardians/{parentId}/labels",
		"/guardians/{parentId}/label-decisions", "/counsel/inquiries"
	);

	@Test
	@DisplayName("Given 58개 프론트 OpenAPI When Apidog 경계로 분할하면 Then 신규 상담·Guardian Labels·Problem Studio revision이 누락되지 않는다")
	void exportsEveryOperationExactlyOnceWithRequiredRoutes() throws IOException {
		Map<String, Object> source = loadDocument();
		Map<String, Object> sourcePaths = asMap(source.get("paths"));
		Map<String, Object> parentCounsel = new LinkedHashMap<>();
		Map<String, Object> problemStudio = new LinkedHashMap<>();
		Map<String, Object> other = new LinkedHashMap<>();
		int sourceOperationCount = 0;

		for (var pathEntry : sourcePaths.entrySet()) {
			Map<String, Object> pathItem = asMap(pathEntry.getValue());
			for (var operationEntry : pathItem.entrySet()) {
				String method = operationEntry.getKey().toLowerCase(Locale.ROOT);
				if (!HTTP_METHODS.contains(method)) continue;
				sourceOperationCount++;
				Map<String, Object> operation = asMap(operationEntry.getValue());
				List<String> tags = stringList(operation.get("tags"));
				Map<String, Object> target = tags.stream().anyMatch(tag -> tag.equals("Counsel") || tag.equals("Guardian Labels"))
					? parentCounsel
					: tags.stream().anyMatch(tag -> tag.equals("Problem Studio") || tag.equals("Problem Generation"))
						? problemStudio : other;
				putOperation(target, pathEntry.getKey(), method, operationEntry.getValue());
			}
		}

		assertThat(sourceOperationCount).isEqualTo(EXPECTED_OPERATION_COUNT);
		assertThat(countOperations(parentCounsel) + countOperations(problemStudio) + countOperations(other))
			.isEqualTo(EXPECTED_OPERATION_COUNT);
		assertThat(parentCounsel.keySet()).containsAll(REQUIRED_GUARDIAN_PATHS);
		assertThat(problemStudio).containsKey(REVISION_PATH);

		Path output = outputDirectory();
		Files.createDirectories(output);
		writeDocument(source, parentCounsel, output.resolve("apidog-parent-counsel.openapi.json"));
		writeDocument(source, problemStudio, output.resolve("apidog-problem-studio.openapi.json"));
		writeDocument(source, other, output.resolve("apidog-frontend-other.openapi.json"));
	}

	private static void putOperation(
		Map<String, Object> target, String path, String method, Object operation
	) {
		@SuppressWarnings("unchecked")
		Map<String, Object> pathItem = (Map<String, Object>) target.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
		pathItem.put(method, operation);
	}

	private static int countOperations(Map<String, Object> paths) {
		return paths.values().stream().mapToInt(value -> (int) asMap(value).keySet().stream()
			.filter(method -> HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))).count()).sum();
	}

	private static void writeDocument(
		Map<String, Object> source, Map<String, Object> paths, Path output
	) throws IOException {
		Map<String, Object> document = new LinkedHashMap<>();
		for (String key : List.of("openapi", "info", "servers", "security", "tags")) {
			document.put(key, source.get(key));
		}
		document.put("paths", paths);
		document.put("components", source.get("components"));
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), document);
		assertThat(new ObjectMapper().readValue(output.toFile(), Map.class)).isNotEmpty();
	}

	private static Path outputDirectory() {
		String configured = System.getenv("CHECKON_APIDOG_OUTPUT");
		return configured == null || configured.isBlank()
			? Path.of("build", "apidog", "frontend")
			: Path.of(configured);
	}

	private static Map<String, Object> loadDocument() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource("openapi/dashboard-api.yaml"));
		factory.afterPropertiesSet();
		return asMap(factory.getObject());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<String> stringList(Object value) {
		assertThat(value).isInstanceOf(List.class);
		return (List<String>) value;
	}
}
