package com.checkon.detection.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.checkon.detection.application.ScheduledDetectionJob;

class DetectionSchedulerTest {

	@Test
	void calculatesTheAnalysisDateInKoreaWhenUtcHasThePreviousDate() {
		ScheduledDetectionJob job = mock(ScheduledDetectionJob.class);
		Clock clock = Clock.fixed(Instant.parse("2026-08-04T15:30:00Z"), ZoneOffset.UTC);

		new DetectionScheduler(clock, job).runDailyDetection();

		verify(job).run(LocalDate.of(2026, 8, 5));
	}

	@Test
	void declaresTheApprovedCronAndZone() throws NoSuchMethodException {
		Method method = DetectionScheduler.class.getMethod("runDailyDetection");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled.cron()).isEqualTo("0 0 2 * * *");
		assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
	}
}
