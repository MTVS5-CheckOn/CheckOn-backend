package com.checkon.member.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * member 경계의 「구현 ↔ 계약」 대조.
 *
 * <p>{@code global/openapi/ImplementedApiOpenApiContractTest} 는 저장소 전체를 훑어
 * {@code dashboard-api.yaml} 과 맞춘다. member 는 별도 계약({@code member-api.yaml})을 가지므로
 * 그 테스트에서 패키지로 제외했고, 잃은 보증을 이 테스트가 대신 진다. 구조는 원본과 같다.</p>
 *
 * <p>🔴 방향이 원본과 다르다. 원본은 양방향 일치(구현 == 문서)를 요구하지만, member 계약은
 * 46개 오퍼레이션을 **미리** 확정해 두고 PR3~PR9 가 하나씩 구현하는 순서다. 그래서 지금은
 * <b>구현 ⊆ 문서</b> 한 방향만 단정한다 — 계약에 없는 경로가 몰래 생기는 것을 막는 것이 목적이다.
 * 반대 방향(문서에만 있고 미구현)은 PR9 까지 정상 상태이므로 단정하지 않고 개수만 남긴다.</p>
 */
class MemberImplementedApiOpenApiContractTest {

	private static final String MEMBER_PACKAGE = "com.checkon.member";
	private static final String CONTRACT = "openapi/member-api.yaml";
	private static final String V1_PREFIX = "/api/v1";

	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace"
	);

	/** PR0 가 확정한 member 계약의 오퍼레이션 수(경로 44 · 오퍼레이션 46). */
	private static final int CONTRACT_OPERATION_COUNT = 46;

	@Test
	@DisplayName("🔴 구현된 member 오퍼레이션은 전부 member-api.yaml 에 있다")
	void everyImplementedMemberOperationIsDocumented() {
		Set<String> documented = documentedOperations();

		Set<String> undocumented = new TreeSet<>(implementedMemberOperations());
		undocumented.removeAll(documented);

		assertThat(undocumented)
			.as("계약에 없는 member 경로가 생겼다. member-api.yaml 을 먼저 고쳐라")
			.isEmpty();
	}

	@Test
	@DisplayName("🔴 계약 오퍼레이션 수가 고정값과 같다 — 계약이 조용히 줄지 않는다")
	void contractOperationCountIsPinned() {
		// 위 대조는 「구현 ⊆ 문서」 한 방향이라, 아직 구현되지 않은 경로가 문서에서 사라져도
		// 잡지 못한다. PR0 가 확정한 46개를 여기 고정해서 그 구멍을 막는다.
		// 🔴 계약을 의도적으로 바꿀 때만 이 숫자를 함께 바꾼다. 맞추려고 낮추면 게이트가 죽는다.
		assertThat(documentedOperations())
			.as("member-api.yaml 을 못 읽거나 경로가 사라지면 대조가 헛돈다")
			.hasSize(CONTRACT_OPERATION_COUNT);
	}

	private Set<String> implementedMemberOperations() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Set<String> operations = new LinkedHashSet<>();

		scanner.findCandidateComponents(MEMBER_PACKAGE).forEach(candidate -> {
			Class<?> controller = loadClass(candidate.getBeanClassName());
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
				collect(operations, classMapping, methodMapping);
			}
		});
		return operations;
	}

	private void collect(
		Set<String> operations,
		RequestMapping classMapping,
		RequestMapping methodMapping
	) {
		for (String basePath : paths(classMapping)) {
			for (String methodPath : paths(methodMapping)) {
				String absolutePath = normalizePath(basePath + methodPath);
				if (!absolutePath.startsWith(V1_PREFIX)) {
					continue;
				}
				String documentedPath = absolutePath.substring(V1_PREFIX.length());
				for (RequestMethod requestMethod : methodMapping.method()) {
					operations.add(requestMethod.name() + " " + documentedPath);
				}
			}
		}
	}

	private Set<String> documentedOperations() {
		Map<String, Object> paths = asMap(loadDocument().get("paths"));
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
		factory.setResources(new ClassPathResource(CONTRACT));
		factory.afterPropertiesSet();
		return factory.getObject();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}
}
