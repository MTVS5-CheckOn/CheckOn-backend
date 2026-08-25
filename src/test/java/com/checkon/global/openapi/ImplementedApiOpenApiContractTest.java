package com.checkon.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

class ImplementedApiOpenApiContractTest {
	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace"
	);

	@Test
	void everyImplementedV1OperationIsDocumentedWithoutStaleOperations() {
		assertThat(documentedOperations())
			.containsExactlyInAnyOrderElementsOf(implementedV1Operations());
	}

	private Set<String> implementedV1Operations() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Set<String> operations = new LinkedHashSet<>();

		scanner.findCandidateComponents("com.checkon").forEach(candidate -> {
			Class<?> controller = loadClass(candidate.getBeanClassName());
			// member 경계는 별도 계약(openapi/member-api.yaml)을 가진다.
			// 그쪽 「구현 ↔ 계약」 대조는 MemberImplementedApiOpenApiContractTest 가 한다.
			if (controller.getPackageName().startsWith("com.checkon.member")) {
				return;
			}
			RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(
				controller, RequestMapping.class
			);
			if (classMapping == null) {
				return;
			}
			for (Method method : controller.getDeclaredMethods()) {
				RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(
					method, RequestMapping.class
				);
				if (methodMapping == null) {
					continue;
				}
				for (String basePath : paths(classMapping)) {
					for (String methodPath : paths(methodMapping)) {
						String absolutePath = normalizePath(basePath + methodPath);
						if (!absolutePath.startsWith("/api/v1")) {
							continue;
						}
						String documentedPath = absolutePath.substring("/api/v1".length());
						for (RequestMethod requestMethod : methodMapping.method()) {
							operations.add(requestMethod.name() + " " + documentedPath);
						}
					}
				}
			}
		});
		return operations;
	}

	private Set<String> documentedOperations() {
		Map<String, Object> document = loadDocument();
		Map<String, Object> paths = asMap(document.get("paths"));
		Set<String> operations = new LinkedHashSet<>();
		paths.forEach((path, pathItemValue) -> asMap(pathItemValue).forEach(
			(method, ignored) -> {
				String normalizedMethod = method.toLowerCase(Locale.ROOT);
				if (HTTP_METHODS.contains(normalizedMethod)) {
					operations.add(normalizedMethod.toUpperCase(Locale.ROOT) + " " + path);
				}
			}
		));
		return operations;
	}

	private String[] paths(RequestMapping mapping) {
		String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
		return paths.length == 0 ? new String[]{""} : paths;
	}

	private String normalizePath(String path) {
		return path.replaceAll("/{2,}", "/");
	}

	private Class<?> loadClass(String className) {
		try {
			return Class.forName(className);
		}
		catch (ClassNotFoundException exception) {
			throw new IllegalStateException("Controller class not found: " + className, exception);
		}
	}

	private Map<String, Object> loadDocument() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource("openapi/dashboard-api.yaml"));
		factory.afterPropertiesSet();
		return factory.getObject();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}
}
