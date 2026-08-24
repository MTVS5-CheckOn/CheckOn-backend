package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DetectionExecutionKeyTest {

	@Test
	void createsDailyKeyFromTenantAliasAndAnalysisDate() {
		DetectionExecutionKey key = DetectionExecutionKey.daily(
			"tn_demo_teacher",
			LocalDate.of(2026, 7, 28)
		);

		assertThat(key.value()).isEqualTo("tn_demo_teacher:2026-07-28");
	}

	@Test
	void sameTenantAndAnalysisDateCreateSameKey() {
		LocalDate analysisDate = LocalDate.of(2026, 7, 28);

		DetectionExecutionKey first = DetectionExecutionKey.daily(
			"tn_demo_teacher",
			analysisDate
		);
		DetectionExecutionKey retry = DetectionExecutionKey.daily(
			"tn_demo_teacher",
			analysisDate
		);

		assertThat(retry).isEqualTo(first);
	}

	@Test
	void differentAnalysisDatesCreateDifferentKeys() {
		DetectionExecutionKey first = DetectionExecutionKey.daily(
			"tn_demo_teacher",
			LocalDate.of(2026, 7, 28)
		);
		DetectionExecutionKey nextDay = DetectionExecutionKey.daily(
			"tn_demo_teacher",
			LocalDate.of(2026, 7, 29)
		);

		assertThat(nextDay).isNotEqualTo(first);
	}

	@Test
	void rejectsBlankTenantAlias() {
		assertThatThrownBy(() -> DetectionExecutionKey.daily(
			" ",
			LocalDate.of(2026, 7, 28)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("tenantAlias must not be blank");
	}

	@Test
	void rejectsMissingAnalysisDate() {
		assertThatThrownBy(() -> DetectionExecutionKey.daily(
			"tn_demo_teacher",
			null
		))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("analysisDate must not be null");
	}
}
