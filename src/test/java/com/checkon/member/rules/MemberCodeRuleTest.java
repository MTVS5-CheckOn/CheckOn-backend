package com.checkon.member.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * member 코드 규칙 게이트. 정본은 {@code docs/member-backend/03_backend_code_rules.md} §11 이다.
 *
 * <p>정적분석 플러그인을 쓰지 않는다 — {@code build.gradle} 은 승우님 소유라 무접촉이고,
 * 의존성 추가 없이 파일 walk + 정규식 + AssertJ 로 같은 일을 한다.</p>
 *
 * <p>🔴 실패 메시지에 위반 파일 경로가 그대로 나오게 만든다. "위반 12건" 만 나오면 아무도 안 고친다.</p>
 */
class MemberCodeRuleTest {

	private static final Path MEMBER = Path.of("src/main/java/com/checkon/member");
	private static final Path ERROR_CODE_DOCUMENT = Path.of("docs/MEMBER_ERROR_CODES.md");
	private static final Path OPEN_ITEM_DOCUMENT = Path.of("docs/MEMBER_OPEN_ITEMS.md");
	private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
	private static final Path ERROR_CODE_SOURCE =
		MEMBER.resolve("common/error/MemberErrorCode.java");

	/** G15 가 「컨텍스트를 열었다」로 인정하는 호출. 셋 다 MemberDatabaseContext 를 거친다. */
	private static final Pattern CONTEXT_OPENERS =
		Pattern.compile("openSubjectContext|setCurrent[A-Z]|withVerified[A-Z]");

	/**
	 * G15 면제 선언. 🔴 이름만 적는 예외 목록이 아니라 <b>읽는 테이블을 열거</b>하게 만든다 —
	 * 게이트가 그 테이블들이 정말 RLS 밖인지 마이그레이션에서 확인한다. 거짓 면제는 red 다.
	 */
	private static final Pattern RLS_WAIVER =
		Pattern.compile("G15-EXEMPT\\(([a-z0-9_,\\s]+)\\)");

	private static final Pattern RLS_ENABLED =
		Pattern.compile("ALTER\\s+TABLE\\s+(\\w+)\\s+ENABLE\\s+ROW\\s+LEVEL\\s+SECURITY",
			Pattern.CASE_INSENSITIVE);

	private static final int MAX_LINES = 400;
	private static final int MAX_LINE_LENGTH = 100;

	/** G8 이 금지하는 오류 코드 리터럴. 🔴 21개 전량이며 enum 과 같은 집합이다. */
	private static final String ERROR_CODES = String.join("|",
		"INVALID_REQUEST", "AUTHENTICATION_REQUIRED", "INVALID_CREDENTIALS", "ACCOUNT_NOT_ACTIVE",
		"ROLE_FORBIDDEN", "STUDENT_ACTIVATION_REQUIRED", "RESOURCE_NOT_FOUND",
		"EMAIL_ALREADY_EXISTS", "IDEMPOTENCY_CONFLICT", "REVISION_CONFLICT",
		"CHILD_ALREADY_LINKED", "ATTEMPT_ALREADY_SUBMITTED", "INVITE_ALREADY_CLAIMED",
		"INVITE_EXPIRED", "SUBMISSION_INCOMPLETE", "RELATIONSHIP_REQUIRED",
		"WORKSHEET_NOT_GRADABLE", "RATE_LIMITED", "INTERNAL", "DEPENDENCY_UNAVAILABLE",
		"DEPENDENCY_TIMEOUT");

	private static List<Path> memberSources() throws IOException {
		try (Stream<Path> walk = Files.walk(MEMBER)) {
			return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static String readString(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/** 정규식에 걸리는 member 소스 경로. 실패 메시지가 곧 수정 목록이 된다. */
	private static List<String> grepFiles(String regex) throws IOException {
		Pattern pattern = Pattern.compile(regex);
		return memberSources().stream()
			.filter(path -> pattern.matcher(readString(path)).find())
			.map(Path::toString)
			.toList();
	}

	@Test
	@DisplayName("G1. 강사 테넌트 컨텍스트를 건드리지 않는다")
	void doesNotTouchTeacherTenantContext() throws IOException {
		assertThat(grepFiles("TeacherTenantDatabaseContext|checkon\\.current_teacher_id"))
			.as("member 는 강사 RLS 경계를 절대 열지 않는다")
			.isEmpty();
	}

	@Test
	@DisplayName("G2. integration 밖에서 남의 패키지를 import 하지 않는다")
	void onlyIntegrationCrossesPackageBoundary() throws IOException {
		Pattern foreignImport = Pattern.compile(
			"import com\\.checkon\\."
				+ "(account|roster|learning|problem|counsel|engagement|dashboard|detection|global)\\.");
		List<String> offenders = memberSources().stream()
			.filter(path -> !path.toString().contains("/integration/"))
			.filter(path -> foreignImport.matcher(readString(path)).find())
			.map(Path::toString)
			.toList();
		assertThat(offenders).as("경계를 넘는 코드는 integration 에만 둔다").isEmpty();
	}

	@Test
	@DisplayName("G3. presentation 이 Repository·Entity 를 직접 참조하지 않는다")
	void presentationDoesNotTouchPersistence() throws IOException {
		Pattern persistence = Pattern.compile("Repository|jakarta\\.persistence|JdbcTemplate");
		List<String> offenders = memberSources().stream()
			.filter(path -> path.toString().contains("/presentation/"))
			.filter(path -> persistence.matcher(readString(path)).find())
			.map(Path::toString)
			.toList();
		assertThat(offenders).as("presentation 은 application 을 통해서만 데이터를 만진다").isEmpty();
	}

	@Test
	@DisplayName("G4. domain 에 Spring 애노테이션을 두지 않는다")
	void domainHasNoSpringAnnotations() throws IOException {
		Pattern springAnnotation = Pattern.compile("import org\\.springframework\\.");
		List<String> offenders = memberSources().stream()
			.filter(path -> path.toString().contains("/domain/"))
			.filter(path -> springAnnotation.matcher(readString(path)).find())
			.map(Path::toString)
			.toList();
		assertThat(offenders).as("domain 은 프레임워크를 모른다 (JPA 는 허용)").isEmpty();
	}

	@Test
	@DisplayName("G5. @Transactional 은 application 에만 둔다")
	void transactionalOnlyInApplication() throws IOException {
		List<String> offenders = memberSources().stream()
			.filter(path -> readString(path).contains("@Transactional"))
			.filter(path -> !path.toString().contains("/application/"))
			.map(Path::toString)
			.toList();
		assertThat(offenders).as("트랜잭션 경계는 application 이 소유한다").isEmpty();
	}

	@Test
	@DisplayName("G6. Lombok 을 쓰지 않는다")
	void doesNotUseLombok() throws IOException {
		assertThat(grepFiles("import lombok")).as("member 는 record 와 명시 생성자를 쓴다").isEmpty();
	}

	@Test
	@DisplayName("G7-a. 클래스가 400줄을 넘지 않는다")
	void classesStayUnder400Lines() throws IOException {
		List<String> offenders = memberSources().stream()
			.filter(path -> countLines(path) > MAX_LINES)
			.map(path -> path + " (" + countLines(path) + "줄)")
			.toList();
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("G7-b. 한 줄이 100자를 넘지 않는다")
	void linesStayUnder100Chars() throws IOException {
		List<String> offenders = memberSources().stream()
			.flatMap(path -> readString(path).lines()
				.filter(line -> expandTabs(line).length() > MAX_LINE_LENGTH)
				.map(line -> path + " :: " + line.strip()))
			.toList();
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("G8. 오류 코드를 문자열 리터럴로 쓰지 않는다")
	void errorCodesComeFromEnum() throws IOException {
		// 🔴 따옴표를 포함해서 찾는다. 따옴표 없이 INTERNAL 을 찾으면
		//    HttpStatus.INTERNAL_SERVER_ERROR 가 걸려서 게이트가 못 쓰게 된다.
		Pattern literal = Pattern.compile("\"(" + ERROR_CODES + ")\"");
		List<String> offenders = memberSources().stream()
			// 🔴 enum 선언 자체는 제외한다. 여기 말고는 리터럴이 있을 곳이 없다.
			.filter(path -> !path.getFileName().toString().equals("MemberErrorCode.java"))
			.filter(path -> literal.matcher(readString(path)).find())
			.map(Path::toString)
			.toList();
		assertThat(offenders).as("코드는 MemberErrorCode enum 에서만 나온다").isEmpty();
	}

	@Test
	@DisplayName("G9. 안건 번호 없는 TODO 를 남기지 않는다")
	void todosCarryOpenItemId() throws IOException {
		assertThat(grepFiles("TODO(?!\\(MB-\\d+\\)|\\(PR\\d+\\))"))
			.as("TODO 는 TODO(MB-05) 또는 TODO(PR3) 형식만 쓴다")
			.isEmpty();
	}

	@Test
	@DisplayName("G10. Instant.now() 를 직접 호출하지 않는다")
	void doesNotCallInstantNowDirectly() throws IOException {
		assertThat(grepFiles("Instant\\.now\\(\\)")).as("시간은 Clock 으로 주입한다").isEmpty();
	}

	@Test
	@DisplayName("G11. 예외를 삼키는 빈 catch 블록을 두지 않는다")
	void noSilentCatch() throws IOException {
		assertThat(grepFiles("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}"))
			.as("삼킨 예외는 장애를 숨긴다")
			.isEmpty();
	}

	@Test
	@DisplayName("G12. Map 을 경계 반환 타입으로 쓰지 않는다")
	void noMapAsBoundaryType() throws IOException {
		assertThat(grepFiles("Map<String,\\s*Object>")).as("경계 타입은 record 로 고정한다").isEmpty();
	}

	@Test
	@DisplayName("G13. enum 상수 집합이 MEMBER_ERROR_CODES.md 와 같다")
	void errorCodeEnumMatchesDocument() throws IOException {
		Set<String> documented = Pattern.compile("`([A-Z][A-Z_]{3,})`")
			.matcher(Files.readString(ERROR_CODE_DOCUMENT))
			.results().map(result -> result.group(1))
			.collect(Collectors.toCollection(TreeSet::new));
		Set<String> declared = Pattern.compile("^\\s*([A-Z][A-Z_]{3,})\\(", Pattern.MULTILINE)
			.matcher(Files.readString(ERROR_CODE_SOURCE))
			.results().map(result -> result.group(1))
			.collect(Collectors.toCollection(TreeSet::new));
		assertThat(declared)
			.as("문서와 enum 이 갈리면 계약·프론트 분기가 조용히 어긋난다")
			.isEqualTo(documented);
	}

	@Test
	@DisplayName("G14. 코드의 TODO(MB-nn) 번호가 MEMBER_OPEN_ITEMS.md 에 실재한다")
	void todoIssueNumbersAreRegistered() throws IOException {
		// 🔴 G9 는 TODO 의 **형식**만 본다 — 번호가 붙어 있으면 통과하고 실재는 안 본다.
		//    PR1 에서 TODO(MB-29) 가 안건 등록 없이 들어간 게 그 구멍이다.
		Set<String> registered = Pattern.compile("MB-\\d+")
			.matcher(Files.readString(OPEN_ITEM_DOCUMENT))
			.results().map(java.util.regex.MatchResult::group)
			.collect(Collectors.toCollection(TreeSet::new));
		Set<String> referenced = memberSources().stream()
			.flatMap(path -> Pattern.compile("TODO\\((MB-\\d+)\\)")
				.matcher(readString(path)).results().map(result -> result.group(1)))
			.collect(Collectors.toCollection(TreeSet::new));
		// 🔴 TODO(PR3) 는 대상이 아니다 — 안건이 아니라 일정이다.
		assertThat(referenced)
			.as("코드가 참조하는 안건 번호는 전부 등재돼 있어야 한다")
			.allSatisfy(number -> assertThat(registered)
				.as("TODO(%s) 가 MEMBER_OPEN_ITEMS.md 에 없다", number)
				.contains(number));
	}

	@Test
	@DisplayName("G15. RLS 테이블을 읽는 application 서비스는 자기 트랜잭션에서 컨텍스트를 연다")
	void memberServicesOpenTheirOwnRlsContext() throws IOException {
		// 🔴 set_config(..., true) 는 트랜잭션 로컬이다(설계 §6-4-4). 리졸버 → 인터셉터 →
		//    서비스가 각각 다른 트랜잭션이라 "앞에서 열었으니 됐다"가 성립하지 않는다.
		//    빠뜨리면 예외가 아니라 **0행**이라 조용하다 — PR3 에서 결함 3건이 여기서 나왔다.
		List<String> offenders = memberSources().stream()
			.filter(path -> path.toString().contains("/application/"))
			.filter(path -> path.getFileName().toString().endsWith("Service.java"))
			.filter(path -> readString(path).contains("Repository"))
			.filter(path -> !CONTEXT_OPENERS.matcher(readString(path)).find())
			.filter(path -> !RLS_WAIVER.matcher(readString(path)).find())
			.map(Path::toString)
			.toList();
		assertThat(offenders)
			.as("Repository 를 쓰는 서비스가 RLS 컨텍스트를 열지 않는다 (설계 §6-4-3·§6-4-4)")
			.isEmpty();
	}

	@Test
	@DisplayName("G15-b. G15 면제가 열거한 테이블은 정말 RLS 밖이다")
	void rlsWaiversNameOnlyUnprotectedTables() throws IOException {
		// 🔴 면제를 "이름 목록"으로 두면 아무나 이름을 넣어 게이트를 무력화한다.
		//    그래서 면제는 **읽는 테이블을 열거**하게 하고, 그 주장을 마이그레이션으로 반증한다.
		Set<String> rlsTables = new TreeSet<>();
		try (Stream<Path> walk = Files.walk(MIGRATIONS)) {
			walk.filter(path -> path.toString().endsWith(".sql"))
				.map(MemberCodeRuleTest::readString)
				.forEach(sql -> RLS_ENABLED.matcher(sql).results()
					.forEach(result -> rlsTables.add(result.group(1).toLowerCase(Locale.ROOT))));
		}
		assertThat(rlsTables).as("마이그레이션에서 RLS 테이블을 하나도 못 찾았다면 정규식이 죽은 것이다")
			.isNotEmpty();

		for (Path path : memberSources()) {
			java.util.regex.Matcher waiver = RLS_WAIVER.matcher(readString(path));
			while (waiver.find()) {
				List<String> claimed = Stream.of(waiver.group(1).split("[,\\s]+"))
					.filter(name -> !name.isBlank()).toList();
				assertThat(claimed).as("%s 의 G15-EXEMPT 가 테이블을 하나도 안 적었다", path).isNotEmpty();
				assertThat(claimed)
					.as("%s 의 G15-EXEMPT 가 RLS 켜진 테이블을 'RLS 밖'이라고 주장한다", path)
					.doesNotContainAnyElementsOf(rlsTables);
			}
		}
	}

	private static long countLines(Path path) {
		return readString(path).lines().count();
	}

	private static String expandTabs(String line) {
		return line.replace("\t", "    ");
	}
}
