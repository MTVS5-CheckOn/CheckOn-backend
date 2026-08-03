package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DetectionTenantKeyTest {

	@Test
	void derivesStableSeparatedDailyKeysFromTeacherProfileIds() {
		UUID teacherA = UUID.fromString("0198c000-0000-7000-8000-000000000001");
		UUID teacherB = UUID.fromString("0198c000-0000-7000-8000-000000000002");
		LocalDate analysisDate = LocalDate.of(2026, 8, 3);

		String tenantA = DetectionTenantKey.fromTeacherProfileId(teacherA).value();
		String tenantB = DetectionTenantKey.fromTeacherProfileId(teacherB).value();

		assertThat(tenantA).isEqualTo("teacher_0198c000000070008000000000000001");
		assertThat(tenantA).isNotEqualTo(tenantB);
		assertThat(DetectionExecutionKey.daily(tenantA, analysisDate).value())
			.isEqualTo(tenantA + ":2026-08-03");
	}
}
