package com.checkon.member.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
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

	private static final Path OPEN_ITEM_DOCUMENT = Path.of("docs/MEMBER_OPEN_ITEMS.md");

	/**
	 * 🔴 계약이 선언했지만 컨트롤러가 받지 않는 쿼리 파라미터. <b>지금은 비어 있다.</b>
	 *
	 * <p>🔴 여기에 넣으려면 {@code "METHOD /path ?name"} 형식으로 적고 <b>MB 번호를 사유로
	 * 남겨라.</b> 번호 없는 예외를 만들면 이 게이트가 쓰레기통이 된다 — 「목록에 있으니까
	 * 괜찮다」가 「왜 없는지 아무도 모른다」와 같은 뜻이 된다.</p>
	 */
	private static final Set<String> IGNORED_QUERY_PARAMETERS = Set.of();

	/**
	 * 🔴 <b>의도적으로 구현하지 않은 오퍼레이션과 그 사유 안건 번호.</b>
	 *
	 * <p>🔴 번호 없는 예외를 만들지 마라. 그러면 이 게이트가 쓰레기통이 된다 —
	 * 「예외 목록에 있으니까 괜찮다」가 「왜 없는지 아무도 모른다」와 같은 뜻이 된다.
	 * 각 번호는 {@code MEMBER_OPEN_ITEMS.md} 에 실재해야 하고
	 * {@code #unimplementedExceptionsCarryRegisteredOpenItems} 가 그것을 확인한다.</p>
	 */
	private static final Map<String, String> UNIMPLEMENTED_BY_DECISION = Map.of(
		// 상담 취소 가능 시점이 미확정이라 V44 에 학부모 UPDATE 정책을 넣지 않았다.
		// 정책 없이 엔드포인트만 만들면 조용히 0행 UPDATE 로 끝난다.
		"POST /member/parents/me/children/{studentId}/consultations/{consultationId}/cancellation",
		"MB-09");

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

	/**
	 * 🔴 <b>반대 방향 — 문서 ⊆ 구현.</b> 계약에 있는 오퍼레이션에 컨트롤러 매핑이 있는가.
	 *
	 * <p>🔴 <b>이 게이트가 없어서 자녀 홈이 여섯 PR 동안 빠져 있었다.</b>
	 * {@code GET /member/parents/me/children/{studentId}/home} 은 계약에도 분기표에도 있었지만
	 * 컨트롤러가 없었다 — 위 「구현 ⊆ 문서」는 <b>구현이 없는 것을 볼 수 없다.</b>
	 * 분기표 커버리지 게이트도 「행마다 테스트」만 보고 <b>엔드포인트 존재</b>는 안 본다.
	 * 두 게이트 사이의 구멍이었다.</p>
	 *
	 * <p>🔴 <b>예외는 목록으로 좁게 관리하고 각 항목에 MB 번호를 단다.</b> 번호 없는 예외를
	 * 허용하면 이 게이트가 쓰레기통이 되고, 다음 사람이 모든 미구현을 「예정된 것」으로 읽는다.
	 * 목록을 늘려서 red 를 없애는 것은 반려다 — 늘리기 전에 <b>왜 빠졌는지</b>를 먼저 답해야 한다.</p>
	 */
	@Test
	@DisplayName("🔴 계약에 있는 member 오퍼레이션은 전부 구현돼 있다 (예외는 MB 번호 필수)")
	void everyDocumentedMemberOperationIsImplemented() {
		Set<String> missing = new TreeSet<>(documentedOperations());
		missing.removeAll(implementedMemberOperations());
		missing.removeAll(UNIMPLEMENTED_BY_DECISION.keySet());

		assertThat(missing)
			.as("계약에 있는데 컨트롤러가 없다. 🔴 예외 목록을 늘리지 말고 왜 빠졌는지 먼저 답해라")
			.isEmpty();
	}

	@Test
	@DisplayName("🔴 미구현 예외는 전부 MEMBER_OPEN_ITEMS.md 에 실재하는 안건이다")
	void unimplementedExceptionsCarryRegisteredOpenItems() throws IOException {
		// 🔴 「번호를 달아라」만으로는 부족하다 — 없는 번호를 달면 그만이다.
		//    코드 규칙 G14 와 같은 방식으로 실재를 확인한다.
		Set<String> registered = Pattern.compile("MB-\\d+")
			.matcher(Files.readString(OPEN_ITEM_DOCUMENT))
			.results().map(java.util.regex.MatchResult::group)
			.collect(java.util.stream.Collectors.toCollection(TreeSet::new));

		assertThat(UNIMPLEMENTED_BY_DECISION).isNotEmpty();
		assertThat(UNIMPLEMENTED_BY_DECISION.values())
			.as("미구현 예외의 MB 번호가 MEMBER_OPEN_ITEMS.md 에 없다")
			.allSatisfy(item -> assertThat(registered).contains(item));

		// 🔴 이미 구현된 것이 예외 목록에 남아 있으면 목록이 썩은 것이다.
		Set<String> stale = new TreeSet<>(UNIMPLEMENTED_BY_DECISION.keySet());
		stale.retainAll(implementedMemberOperations());
		assertThat(stale).as("구현됐는데 예외 목록에 남아 있다 — 목록에서 빼라").isEmpty();
	}

	/**
	 * 🔴 <b>한 층 아래 — 선언된 쿼리 파라미터를 컨트롤러가 실제로 받는가.</b>
	 *
	 * <p>🔴 <b>이 게이트가 없어서 {@code teacherId} 가 세 경로에서 조용히 무시됐다.</b>
	 * 위 「문서 ⊆ 구현」은 <b>오퍼레이션이 있는가</b>만 본다 — 오퍼레이션은 있는데 계약이
	 * 선언한 파라미터를 안 받는 것은 통과한다. {@code /home} 누락과 같은 병이 한 층 아래에서
	 * 반복된 것이다.</p>
	 *
	 * <p>🔴 <b>이 판정은 느슨하다.</b> 「같은 이름의 {@code @RequestParam} 을 선언했는가」이지
	 * <b>「그 값이 실제로 필터로 걸리는가」가 아니다.</b> 파라미터를 받아 놓고 버려도 여기는
	 * 통과한다 — 그 층은 통합 테스트가 맡는다(값이 다른지 단언하는 형태).
	 * 느슨한 걸 엄격한 척하지 않는다. 이 게이트가 잡는 것은 <b>선언조차 없는 경우</b>뿐이다.</p>
	 *
	 * <p>🔴 {@code in: path} 는 보지 않는다 — 경로 매핑이 이미 강제한다.
	 * {@code in: header}({@code Idempotency-Key} 등)도 보지 않는다.
	 * {@code @RequestHeader} 없이 {@code HttpServletRequest} 로 읽는 선례가 있어
	 * 이름 대조가 오탐을 낸다.</p>
	 */
	@Test
	@DisplayName("🔴 계약이 선언한 쿼리 파라미터를 컨트롤러가 받는다")
	void everyDocumentedQueryParameterIsAccepted() {
		Map<String, Set<String>> documented = documentedQueryParameters();
		Map<String, Set<String>> accepted = acceptedQueryParameters();

		List<String> missing = new java.util.ArrayList<>();
		documented.forEach((operation, params) -> {
			if (UNIMPLEMENTED_BY_DECISION.containsKey(operation)) {
				return;
			}
			Set<String> taken = accepted.getOrDefault(operation, Set.of());
			for (String param : params) {
				if (!taken.contains(param) && !IGNORED_QUERY_PARAMETERS
					.contains(operation + " ?" + param)) {
					missing.add(operation + " ?" + param);
				}
			}
		});

		assertThat(missing)
			.as("계약이 선언한 쿼리 파라미터를 컨트롤러가 안 받는다 —"
				+ " 🔴 예외 목록을 늘리지 말고 왜 안 받는지 먼저 답해라")
			.isEmpty();
	}

	/**
	 * 🔴 <b>게이트가 무엇을 봤는지 스스로 센다.</b>
	 *
	 * <p>🔴 <b>왜 필요한가 — 고의 파괴로 실측했다.</b> 파라미터 대조 게이트에서 오퍼레이션
	 * 하나를 훑지 않게 만들고 <b>그 오퍼레이션에 실제 구멍</b>을 함께 넣었더니 게이트가
	 * <b>green 이었다</b>. 이빨이 아니라 <b>시야</b>의 구멍이다 — 훑는 범위를 좁히면 게이트
	 * 자신은 아무 말도 못 한다.</p>
	 *
	 * <p>대조 기준을 <b>파서를 거치지 않은 원문</b>에서 따로 뽑는다. 계약 원문에서
	 * {@code parameters/TeacherIdFilter} 를 참조한 횟수와, 파서가 {@code teacherId} 를 찾아낸
	 * 오퍼레이션 수가 같아야 한다. 파서 쪽을 좁히면 두 수가 갈려 red 가 난다.</p>
	 */
	@Test
	@DisplayName("🔴 파라미터 게이트가 계약 전체를 실제로 훑었다 (시야 자체를 검증)")
	void queryParameterGateActuallyScansTheWholeContract() throws IOException {
		// 🔴 파서를 거치지 않은 **원문**을 읽는다. 같은 코드 경로로 세면 대조가 아니다.
		String raw = Files.readString(
			Path.of("src/main/resources/openapi/member-api.yaml"));
		long referencedInContract = Pattern.compile("parameters/TeacherIdFilter")
			.matcher(raw).results().count();
		long seenByParser = documentedQueryParameters().values().stream()
			.filter(names -> names.contains("teacherId")).count();

		assertThat(referencedInContract)
			.as("계약 원문에서 TeacherIdFilter 참조를 하나도 못 찾았다면 이 대조가 헛돈다")
			.isPositive();
		assertThat(seenByParser)
			.as("파서가 훑은 오퍼레이션 수가 계약 원문의 참조 수와 다르다 —"
				+ " 🔴 게이트의 시야가 좁아졌다")
			.isEqualTo(referencedInContract);
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

	/** 계약이 오퍼레이션마다 선언한 {@code in: query} 파라미터 이름. */
	private Map<String, Set<String>> documentedQueryParameters() {
		Map<String, Object> components = asMap(loadDocument().get("components"));
		Map<String, Object> shared = asMap(components.get("parameters"));
		Map<String, Object> paths = asMap(loadDocument().get("paths"));
		Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
		paths.forEach((path, pathItemValue) -> asMap(pathItemValue).forEach(
			(method, operationValue) -> {
				String normalizedMethod = method.toLowerCase(Locale.ROOT);
				if (!HTTP_METHODS.contains(normalizedMethod)) {
					return;
				}
				String key = normalizedMethod.toUpperCase(Locale.ROOT) + " " + path;
				Set<String> names = new TreeSet<>();
				for (Object parameter : asList(asMap(operationValue).get("parameters"))) {
					Map<String, Object> resolved = resolveParameter(parameter, shared);
					if ("query".equals(resolved.get("in"))) {
						names.add(String.valueOf(resolved.get("name")));
					}
				}
				if (!names.isEmpty()) {
					result.put(key, names);
				}
			}));
		return result;
	}

	/** {@code $ref: '#/components/parameters/X'} 를 실제 정의로 편다. */
	private Map<String, Object> resolveParameter(Object parameter, Map<String, Object> shared) {
		Map<String, Object> node = asMap(parameter);
		Object ref = node.get("$ref");
		if (ref == null) {
			return node;
		}
		String name = String.valueOf(ref);
		return asMap(shared.get(name.substring(name.lastIndexOf('/') + 1)));
	}

	/** 컨트롤러 메서드가 실제로 선언한 {@code @RequestParam} 이름. */
	private Map<String, Set<String>> acceptedQueryParameters() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Map<String, Set<String>> result = new java.util.LinkedHashMap<>();

		scanner.findCandidateComponents(MEMBER_PACKAGE).forEach(candidate -> {
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
				Set<String> names = requestParameterNames(method);
				Set<String> operations = new LinkedHashSet<>();
				collect(operations, classMapping, methodMapping);
				operations.forEach(operation ->
					result.computeIfAbsent(operation, ignored -> new TreeSet<>()).addAll(names));
			}
		});
		return result;
	}

	private Set<String> requestParameterNames(Method method) {
		Set<String> names = new TreeSet<>();
		for (java.lang.reflect.Parameter parameter : method.getParameters()) {
			RequestParam annotation = parameter.getAnnotation(RequestParam.class);
			if (annotation == null) {
				continue;
			}
			String declared = annotation.name().isEmpty() ? annotation.value() : annotation.name();
			names.add(declared.isEmpty() ? parameter.getName() : declared);
		}
		return names;
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

	/** 🔴 {@code parameters} 가 없는 오퍼레이션이 흔하다 — 그때는 빈 목록이지 실패가 아니다. */
	@SuppressWarnings("unchecked")
	private List<Object> asList(Object value) {
		if (value == null) {
			return List.of();
		}
		assertThat(value).isInstanceOf(List.class);
		return (List<Object>) value;
	}
}
