package com.checkon.counsel.infrastructure.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.checkon.counsel.application.CounselDraftPollingJob;

/**
 * Off by default ({@code checkon.counsel.polling.enabled=false}) — this is
 * bookkeeping freshness, not a functional requirement (see
 * {@link CounselDraftPollingJob}), and firing it unconditionally would call
 * the counsel AI client during every {@code @SpringBootTest} in the suite.
 */
@Component
@ConditionalOnProperty(prefix = "checkon.counsel.polling", name = "enabled", havingValue = "true")
public class CounselDraftPollingScheduler {

	private final CounselDraftPollingJob job;

	public CounselDraftPollingScheduler(CounselDraftPollingJob job) {
		this.job = job;
	}

	@Scheduled(fixedDelayString = "${checkon.counsel.polling.interval:30s}")
	public void poll() {
		job.pollAll();
	}
}
