package com.checkon.publication.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>G1 의 거울.</b> member 게이트가 「member 는 강사 컨텍스트를 열지 마라」를 막는다면,
 * 이 게이트는 「publication 은 학부모·학생 컨텍스트를 열지 마라」를 막는다.
 *
 * <p>🔴 <b>왜 그게 불변식인가</b> — V45 의 정책은 전부 {@code PERMISSIVE} 다. PostgreSQL 은
 * 같은 명령에 붙은 PERMISSIVE 정책들을 <b>OR 로 합친다.</b> 한 트랜잭션에서 강사 컨텍스트와
 * 학부모 컨텍스트를 <b>둘 다</b> 열면 「강사 자기 것」과 「학부모 자기 자녀 것」이 동시에
 * 참이 되어 격리가 무너진다. 「강사만 연다」는 편의가 아니라 그 합집합을 막는 것이다.</p>
 *
 * <p>🔴 <b>이 판정은 느슨하다.</b> 소스 파일의 <b>문자열</b>을 찾을 뿐이다 — 리플렉션·문자열
 * 조립·다른 빈을 거친 우회는 못 잡는다. 엄밀히 하려면 런타임에 세션 변수를 훔쳐봐야 하는데
 * 그건 테스트가 프로덕션 커넥션을 가로채야 한다. 느슨한 걸 엄격한 척하지 않는다 —
 * 이 게이트가 잡는 것은 <b>직접 부르는 경우</b>뿐이다.</p>
 */
class PublicationContextRuleTest {

	private static final Path PUBLICATION = Path.of("src/main/java/com/checkon/publication");

	/**
	 * 🔴 학부모·학생 주체를 여는 수단 전량. {@code MemberDatabaseContext} 는 그 셋을 모두
	 * 여는 관문이라 이름만으로 금지한다.
	 */
	private static final Pattern FORBIDDEN = Pattern.compile(
		"MemberDatabaseContext"
			+ "|current_checkon_parent_id|current_checkon_student_id"
			+ "|current_checkon_scope_student_id|current_checkon_scope_account_id"
			+ "|checkon\\.current_parent_id|checkon\\.current_student_id"
			+ "|checkon\\.current_account_id|checkon\\.scope_");

	@Test
	@DisplayName("🔴 publication 은 학부모·학생 컨텍스트를 열지 않는다 (PERMISSIVE OR 방지)")
	void publicationNeverOpensParentOrStudentContext() throws IOException {
		List<String> offenders = sources()
			.filter(path -> FORBIDDEN.matcher(read(path)).find())
			.map(Path::toString)
			.toList();

		assertThat(offenders)
			.as("publication 이 학부모·학생 컨텍스트를 연다 — PERMISSIVE 정책이 OR 로 합쳐져"
				+ " 격리가 무너진다")
			.isEmpty();
	}

	/**
	 * 🔴 <b>게이트가 무엇을 봤는지 스스로 센다.</b>
	 *
	 * <p>PR #112 에서 「이빨은 있는데 시야에 구멍」인 게이트를 실제로 만들었다 — 훑는 범위를
	 * 좁히면 게이트 자신은 아무 말도 못 했다. 그래서 여기서는 <b>훑은 파일 수</b>와
	 * <b>실제로 존재하는 파일 수</b>를 따로 세어 맞춘다. 위 검사의 {@code sources()} 를
	 * 좁히면 두 수가 갈려 red 가 난다.</p>
	 */
	@Test
	@DisplayName("🔴 이 게이트가 publication 전체를 실제로 훑었다 (시야 자체를 검증)")
	void gateActuallyScansTheWholePackage() throws IOException {
		long scanned = sources().count();
		// 🔴 대조 기준은 sources() 를 거치지 않고 따로 센다. 같은 함수로 두 번 세면 대조가 아니다.
		long onDisk;
		try (Stream<Path> walk = Files.walk(PUBLICATION)) {
			onDisk = walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.count();
		}
		assertThat(onDisk).as("publication 에 자바 파일이 없다면 이 게이트는 아무것도 안 지킨다")
			.isPositive();
		assertThat(scanned).as("게이트가 훑은 파일 수가 실제 파일 수와 다르다 — 시야가 좁아졌다")
			.isEqualTo(onDisk);
	}

	@Test
	@DisplayName("🔴 publication 은 승우님 강사 컨텍스트를 가져다 쓰되 새로 만들지 않는다")
	void publicationReusesTeacherTenantContext() throws IOException {
		List<String> importing = sources()
			.filter(path -> read(path)
				.contains("com.checkon.global.persistence.TeacherTenantDatabaseContext"))
			.map(Path::toString)
			.toList();

		assertThat(importing)
			.as("강사 컨텍스트를 여는 곳이 없다 — 그러면 승우님 원장이 조용히 0행이다")
			.isNotEmpty();
		// 🔴 같은 일을 하는 클래스를 새로 만들지 않았는가.
		List<String> reimplemented = sources()
			.filter(path -> read(path).contains("checkon.current_teacher_id"))
			.map(Path::toString)
			.toList();
		assertThat(reimplemented)
			.as("세션 변수를 직접 세팅한다 — TeacherTenantDatabaseContext 를 쓰지 않고 흉내 냈다")
			.isEmpty();
	}

	private static Stream<Path> sources() throws IOException {
		try (Stream<Path> walk = Files.walk(PUBLICATION)) {
			return walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.toList().stream();
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
