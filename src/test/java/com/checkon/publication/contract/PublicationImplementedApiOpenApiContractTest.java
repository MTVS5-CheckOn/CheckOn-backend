package com.checkon.publication.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

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
 * publication 경계의 「구현 ↔ 계약」 대조.
 *
 * <p>🔴 <b>왜 필요한가 — 승우님 게이트가 우리를 보고 있었고, 그 사실을 우리가 몰랐다.</b>
 * {@code global/openapi/ImplementedApiOpenApiContractTest} 는 {@code com.checkon} 전체의
 * {@code @RestController} 를 훑어 {@code /api/v1/**} 오퍼레이션이 전부
 * {@code dashboard-api.yaml} 에 있어야 한다고 단언한다. 예외는 {@code com.checkon.member}
 * 하나뿐이다. publication 이 엔드포인트를 처음 만들자 그 테스트가 red 가 됐다(W2 · CI 실측).</p>
 *
 * <p>🔴 member 는 같은 문제를 <b>패키지 예외 + 자기 대조 테스트</b>로 풀었다
 * ({@code MemberImplementedApiOpenApiContractTest}). 예외로 잃은 보증을 자기 게이트가 대신
 * 진다는 구조다. <b>이 테스트가 publication 쪽의 그 대신하는 게이트다</b> — 예외를 받든
 * 못 받든 우리 계약과 구현이 갈리는 것은 우리가 잡아야 한다.</p>
 *
 * <p>🔴 <b>양방향이다.</b> member 는 46개를 미리 확정하고 PR 마다 하나씩 구현하는 순서라
 * 「구현 ⊆ 문서」 한 방향만 봤지만, publication 계약은 <b>3개를 이번에 다 구현했다.</b>
 * 그래서 문서에만 있는 것도 red 다 — 없는 걸 계약에 적어 두면 프론트가 있는 줄 안다.</p>
 */
class PublicationImplementedApiOpenApiContractTest {

	private static final String PUBLICATION_PACKAGE = "com.checkon.publication";
	private static final String CONTRACT = "openapi/member-teacher-api.yaml";
	private static final Path CONTRACT_SOURCE =
		Path.of("src/main/resources/openapi/member-teacher-api.yaml");
	private static final String V1_PREFIX = "/api/v1";

	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace"
	);

	@Test
	@DisplayName("🔴 구현과 계약이 정확히 일치한다 (양방향)")
	void implementedOperationsMatchTheContractExactly() {
		assertThat(implementedOperations())
			.as("publication 구현과 member-teacher-api.yaml 이 갈렸다")
			.containsExactlyInAnyOrderElementsOf(documentedOperations());
	}

	/**
	 * 🔴 <b>게이트가 무엇을 봤는지 스스로 센다.</b> 스캐너가 우리 컨트롤러를 하나도 못 찾으면
	 * 위 단언은 「빈 집합 == 빈 집합」이 되어 <b>green 인 채로 아무것도 안 지킨다.</b>
	 * 클래스 레벨 {@code @RequestMapping} 을 떼는 것만으로 그 상태가 된다 —
	 * 승우님 스캐너가 그 조건으로 컨트롤러를 건너뛰므로, <b>그 회피는 실재하는 유혹이다.</b>
	 * 여기서 개수를 원문과 대조해 그 길을 막는다.
	 */
	@Test
	@DisplayName("🔴 게이트가 계약 전체를 실제로 훑었다 (시야 자체를 검증)")
	void gateActuallyScansBothSides() throws IOException {
		// 🔴 파서를 거치지 않은 **원문**에서 따로 센다. 같은 코드 경로로 세면 대조가 아니다.
		long pathsInRawContract = Pattern.compile("(?m)^  /[A-Za-z0-9{}/_-]+:$")
			.matcher(Files.readString(CONTRACT_SOURCE)).results().count();

		assertThat(pathsInRawContract).as("계약 원문에서 경로를 하나도 못 찾았다").isPositive();
		assertThat(documentedOperations())
			.as("파서가 찾은 오퍼레이션이 원문 경로 수보다 적다 — 시야가 좁아졌다")
			.hasSizeGreaterThanOrEqualTo((int) pathsInRawContract);
		assertThat(implementedOperations())
			.as("스캐너가 publication 컨트롤러를 하나도 못 찾았다 —"
				+ " 클래스 레벨 @RequestMapping 이 사라지면 이 게이트가 조용히 무력해진다")
			.isNotEmpty();
	}

	// ──────────────────────────── 수집 ────────────────────────────

	/** 🔴 승우님 원본과 같은 방식으로 모은다. 방식이 다르면 두 게이트의 판정이 갈린다. */
	private Set<String> implementedOperations() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Set<String> operations = new LinkedHashSet<>();

		scanner.findCandidateComponents(PUBLICATION_PACKAGE).forEach(candidate -> {
			Class<?> controller = loadClass(candidate.getBeanClassName());
			RequestMapping classMapping =
				AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
			if (classMapping == null) {
				return;
			}
			for (Method method : controller.getDeclaredMethods()) {
				RequestMapping methodMapping =
					AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
				if (methodMapping == null) {
					continue;
				}
				for (String basePath : paths(classMapping)) {
					for (String methodPath : paths(methodMapping)) {
						String absolute = (basePath + methodPath).replaceAll("/{2,}", "/");
						if (!absolute.startsWith(V1_PREFIX)) {
							continue;
						}
						String documented = absolute.substring(V1_PREFIX.length());
						for (RequestMethod requestMethod : methodMapping.method()) {
							operations.add(requestMethod.name() + " " + documented);
						}
					}
				}
			}
		});
		return operations;
	}

	private Set<String> documentedOperations() {
		var factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource(CONTRACT));
		factory.afterPropertiesSet();
		Map<String, Object> paths = asMap(factory.getObject().get("paths"));
		Set<String> operations = new TreeSet<>();
		paths.forEach((path, item) -> asMap(item).forEach((method, ignored) -> {
			String normalized = method.toLowerCase(Locale.ROOT);
			if (HTTP_METHODS.contains(normalized)) {
				operations.add(normalized.toUpperCase(Locale.ROOT) + " " + path);
			}
		}));
		return operations;
	}

	private String[] paths(RequestMapping mapping) {
		String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
		return paths.length == 0 ? new String[] {""} : paths;
	}

	private Class<?> loadClass(String className) {
		try {
			return Class.forName(className);
		}
		catch (ClassNotFoundException exception) {
			throw new IllegalStateException("Controller class not found: " + className, exception);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}
}
