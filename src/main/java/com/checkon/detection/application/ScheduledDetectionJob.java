package com.checkon.detection.application;

import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.checkon.detection.application.OperationalDetectionRunService.Outcome;

@Service
public class ScheduledDetectionJob {

	private static final Logger log = LoggerFactory.getLogger(ScheduledDetectionJob.class);

	private final ScheduledDetectionTargetProvider targetProvider;
	private final OperationalDetectionRunService detectionRunService;

	public ScheduledDetectionJob(
		ScheduledDetectionTargetProvider targetProvider,
		OperationalDetectionRunService detectionRunService
	) {
		this.targetProvider = targetProvider;
		this.detectionRunService = detectionRunService;
	}

	public Summary run(LocalDate analysisDate) {
		int succeeded = 0;
		int duplicate = 0;
		int noLearningRecords = 0;
		int failed = 0;

		for (UUID teacherProfileId : targetProvider.findActiveTeacherProfileIds()) {
			try {
				Outcome outcome = detectionRunService
					.execute(teacherProfileId, analysisDate)
					.outcome();
				if (outcome == Outcome.SUCCEEDED) succeeded++;
				else duplicate++;
			}
			catch (NoLearningRecordsException exception) {
				noLearningRecords++;
			}
			catch (DetectionRunAlreadyRunningException | DataIntegrityViolationException exception) {
				duplicate++;
				log.info("Scheduled Detection duplicate ignored: teacherProfileId={}, analysisDate={}",
					teacherProfileId, analysisDate);
			}
			catch (RuntimeException exception) {
				failed++;
				log.error("Scheduled Detection failed: teacherProfileId={}, analysisDate={}, errorType={}",
					teacherProfileId, analysisDate, exception.getClass().getSimpleName());
			}
		}

		Summary summary = new Summary(succeeded, duplicate, noLearningRecords, failed);
		log.info("Scheduled Detection completed: analysisDate={}, succeeded={}, duplicate={}, noLearningRecords={}, failed={}",
			analysisDate, succeeded, duplicate, noLearningRecords, failed);
		return summary;
	}

	public record Summary(int succeeded, int duplicate, int noLearningRecords, int failed) {
		public int total() {
			return succeeded + duplicate + noLearningRecords + failed;
		}
	}
}
