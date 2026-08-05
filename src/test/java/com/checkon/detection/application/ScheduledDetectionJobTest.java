package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.checkon.detection.application.OperationalDetectionRunService.OperationalDetectionRun;
import com.checkon.detection.application.OperationalDetectionRunService.Outcome;
import com.checkon.detection.domain.DetectionRunStatus;

class ScheduledDetectionJobTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 5);

	@Test
	void isolatesTeacherFailuresAndAggregatesEveryResult() {
		UUID success = UUID.fromString("0198c000-0000-7000-8000-000000000001");
		UUID duplicate = UUID.fromString("0198c000-0000-7000-8000-000000000002");
		UUID empty = UUID.fromString("0198c000-0000-7000-8000-000000000003");
		UUID failed = UUID.fromString("0198c000-0000-7000-8000-000000000004");
		ScheduledDetectionTargetProvider targets = mock(ScheduledDetectionTargetProvider.class);
		OperationalDetectionRunService service = mock(OperationalDetectionRunService.class);
		when(targets.findActiveTeacherProfileIds())
			.thenReturn(List.of(success, duplicate, empty, failed));
		when(service.execute(success, DATE)).thenReturn(result(success, Outcome.SUCCEEDED));
		when(service.execute(duplicate, DATE))
			.thenReturn(result(duplicate, Outcome.ALREADY_COMPLETED));
		when(service.execute(empty, DATE)).thenThrow(new NoLearningRecordsException());
		when(service.execute(failed, DATE)).thenThrow(new IllegalStateException("safe test failure"));

		ScheduledDetectionJob.Summary summary =
			new ScheduledDetectionJob(targets, service).run(DATE);

		assertThat(summary.succeeded()).isEqualTo(1);
		assertThat(summary.duplicate()).isEqualTo(1);
		assertThat(summary.noLearningRecords()).isEqualTo(1);
		assertThat(summary.failed()).isEqualTo(1);
		assertThat(summary.total()).isEqualTo(4);
		verify(service).execute(failed, DATE);
	}

	@Test
	void finishesNormallyWhenThereAreNoTargets() {
		ScheduledDetectionTargetProvider targets = mock(ScheduledDetectionTargetProvider.class);
		OperationalDetectionRunService service = mock(OperationalDetectionRunService.class);
		when(targets.findActiveTeacherProfileIds()).thenReturn(List.of());

		assertThat(new ScheduledDetectionJob(targets, service).run(DATE).total())
			.isZero();
	}

	private OperationalDetectionRun result(UUID runId, Outcome outcome) {
		return new OperationalDetectionRun(
			runId, DetectionRunStatus.SUCCEEDED, DATE, false, 0, outcome
		);
	}
}
