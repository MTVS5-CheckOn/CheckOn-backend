package com.checkon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 마이그레이션 버전이 서로 겹치지 않는지 본다.
 *
 * <p>🔴 <b>일부러 Spring 컨텍스트를 띄우지 않는다.</b> 버전이 겹치면 Flyway 가 기동 단계에서
 * 먼저 죽어 {@code @SpringBootTest} 클래스의 테스트가 전부
 * {@code ParameterResolutionException} 으로 무너진다. 그 안에 이 단언을 두면 <b>실행조차
 * 되지 않는다</b> — 통과하거나, 아니면 다른 이유로 죽는다.</p>
 *
 * <p>탐지는 Flyway 가 한다. 이 테스트가 하는 일은 <b>진단</b>이다. PR #86 때 242개가 무너진
 * 스택을 파고 나서야 "V33 이 두 개"라는 걸 알았다. 여기서는 겹친 파일 이름이 바로 나온다.</p>
 *
 * <p>DB 에 적용된 버전과 리소스 최고 버전을 맞추는 검증은 DB 가 필요하므로
 * {@link CheckOnApplicationTests} 에 있다. 각 단언은 실패할 수 있는 자리에만 둔다.</p>
 */
class MigrationVersionUniquenessTest {

	private static final String LOCATION_PATTERN = "classpath*:db/migration/V*__*.sql";
	private static final Pattern VERSIONED_FILENAME = Pattern.compile("^V(\\d+)__.+\\.sql$");

	@Test
	@DisplayName("🔴 마이그레이션 버전이 서로 중복되지 않는다")
	void migrationVersionsAreUnique() throws IOException {
		Map<Integer, List<String>> byVersion = migrationsByVersion();

		List<String> duplicates = byVersion.entrySet().stream()
			.filter(entry -> entry.getValue().size() > 1)
			.map(entry -> "V" + entry.getKey() + " -> " + entry.getValue())
			.toList();

		assertThat(duplicates)
			.as("같은 번호를 쓰는 마이그레이션이 있다. 어느 쪽을 다음 번호로 옮길지 정해야 한다")
			.isEmpty();
	}

	@Test
	@DisplayName("마이그레이션을 하나도 못 읽으면 위 검증이 헛돈다")
	void migrationsAreDiscovered() throws IOException {
		assertThat(migrationVersions())
			.as("%s 로 아무것도 못 읽었다. 경로가 바뀌었는지 확인해라", LOCATION_PATTERN)
			.isNotEmpty();
	}

	/**
	 * 리소스에 있는 마이그레이션 버전 전량.
	 *
	 * <p>🔴 숫자로 다룬다. 문자열로 비교하면 {@code "9" > "10"} 이 되는데, 한 자리(V1~V9)와
	 * 두 자리가 이미 섞여 있어 곧바로 틀린다.</p>
	 *
	 * <p>🔴 리소스가 돌아오는 순서는 정렬돼 있지 않다(실측: 첫 원소가 V10). 최고 버전이
	 * 필요하면 {@code Collections.max} 를 써라 — 첫 원소를 쓰면 안 된다.</p>
	 */
	static List<Integer> migrationVersions() throws IOException {
		List<Integer> versions = new ArrayList<>();
		migrationsByVersion().forEach((version, files) ->
			files.forEach(ignored -> versions.add(version)));
		return versions;
	}

	/** 버전 → 그 버전을 쓰는 파일 이름들. 중복이면 값이 둘 이상이다. */
	private static Map<Integer, List<String>> migrationsByVersion() throws IOException {
		Resource[] migrations = new PathMatchingResourcePatternResolver()
			.getResources(LOCATION_PATTERN);
		Map<Integer, List<String>> byVersion = new TreeMap<>();
		for (Resource migration : migrations) {
			String filename = Objects.requireNonNull(migration.getFilename());
			Matcher matcher = VERSIONED_FILENAME.matcher(filename);
			assertThat(matcher.matches()).as("migration filename %s", filename).isTrue();
			byVersion.computeIfAbsent(Integer.valueOf(matcher.group(1)), key -> new ArrayList<>())
				.add(filename);
		}
		return byVersion;
	}
}
