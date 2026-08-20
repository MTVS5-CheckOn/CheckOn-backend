package com.checkon.counsel.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.checkon.detection.application.ScheduledDetectionTargetProvider;

/**
 * Refreshes locally stored {@code job_phase} for counsel draft jobs that are
 * not yet terminal. This does not unstick a {@code queued} job — per §0-3 of
 * the counsel contract, GET never runs the job, only another POST can — it
 * only keeps {@code counsel_draft_jobs} from going stale between the times a
 * teacher actually opens the draft (so a future inbox listing does not show
 * a phase that changed hours ago).
 *
 * <p>The actual per-teacher refresh lives on {@link CounselDraftService}
 * (a different, proxied bean) rather than here, so its
 * {@code @Transactional} and RLS tenant context actually take effect —
 * calling it on {@code this} would be a self-invocation the Spring proxy
 * never sees.
 */
@Service
public class CounselDraftPollingJob {

	private static final Logger log = LoggerFactory.getLogger(CounselDraftPollingJob.class);

	private final ScheduledDetectionTargetProvider teacherProvider;
	private final CounselDraftService drafts;

	public CounselDraftPollingJob(ScheduledDetectionTargetProvider teacherProvider, CounselDraftService drafts) {
		this.teacherProvider = teacherProvider;
		this.drafts = drafts;
	}

	public Summary pollAll() {
		int teachersPolled = 0;
		int jobsRefreshed = 0;
		int failed = 0;
		for (UUID teacherId : teacherProvider.findActiveTeacherProfileIds()) {
			try {
				jobsRefreshed += drafts.refreshNonTerminalJobs(teacherId);
				teachersPolled++;
			}
			catch (RuntimeException exception) {
				failed++;
				log.warn("Counsel draft polling failed: teacherId={}, errorType={}",
					teacherId, exception.getClass().getSimpleName());
			}
		}
		Summary summary = new Summary(teachersPolled, jobsRefreshed, failed);
		if (jobsRefreshed > 0 || failed > 0) {
			log.info("Counsel draft polling: teachersPolled={}, jobsRefreshed={}, failed={}",
				teachersPolled, jobsRefreshed, failed);
		}
		return summary;
	}

	public record Summary(int teachersPolled, int jobsRefreshed, int failed) {
	}
}
