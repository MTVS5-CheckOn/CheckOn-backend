package com.checkon.detection.infrastructure.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.checkon.detection.application.ScheduledDetectionJob;

@Component
public class DetectionScheduler {

	public static final String CRON = "0 10 2 * * *";
	public static final String ZONE = "Asia/Seoul";
	private static final ZoneId SERVICE_ZONE = ZoneId.of(ZONE);

	private final Clock clock;
	private final ScheduledDetectionJob job;

	public DetectionScheduler(Clock clock, ScheduledDetectionJob job) {
		this.clock = clock;
		this.job = job;
	}

	@Scheduled(cron = CRON, zone = ZONE)
	public void runDailyDetection() {
		job.run(LocalDate.now(clock.withZone(SERVICE_ZONE)));
	}
}
