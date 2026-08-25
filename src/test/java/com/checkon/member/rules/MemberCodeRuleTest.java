package com.checkon.member.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	private static final Path ERROR_CODE_SOURCE =
		MEMBER.resolve("common/error/MemberErrorCode.java");

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

	private static long countLines(Path path) {
		return readString(path).lines().count();
	}

	private static String expandTabs(String line) {
		return line.replace("\t", "    ");
	}
}
