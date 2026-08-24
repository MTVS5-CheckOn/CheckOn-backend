package com.checkon.detection.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskDetectionSeedContractTest {

	private static final Path SEED = Path.of(
		"scripts", "demo", "risk-detection", "checkon_seed.sql"
	);

	@Test
	@DisplayName("Given AI 팀 시드, When 저장소 계약을 검사하면, Then 수동 테넌트 매핑과 트랜잭션 경계를 유지한다")
	void givenAiSeed_whenCheckingRepositoryContract_thenKeepsManualSafetyBoundary()
		throws IOException {
		String sql = Files.readString(SEED);
		long studentMappings = Pattern.compile(
			"\\('st_[0-9]{2}', '<채우세요>'\\)"
		).matcher(sql).results().count();

		assertThat(studentMappings).isEqualTo(17);
		assertThat(sql)
			.contains("\\set ON_ERROR_STOP on")
			.contains("BEGIN;")
			.contains("uuidv7()")
			.doesNotContain("gen_random_uuid()")
			.contains("detection_assignment_week_summaries")
			.contains("detection_student_status_history")
			.contains("'paused', 'returned'")
			.contains("sa.teacher_id = :'teacher_id'::uuid");
		assertThat(Pattern.compile("(?m)^COMMIT;\\s*$").matcher(sql).find()).isFalse();
	}
}
