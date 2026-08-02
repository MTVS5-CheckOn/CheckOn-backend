package com.checkon.detection.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

@Service
public class DetectionAttemptCoordinator {

	private final DetectionRunRepository runRepository;
	private final DetectionIdGenerator idGenerator;
	private final TeacherTenantDatabaseContext tenantDatabaseContext;

	public DetectionAttemptCoordinator(
		DetectionRunRepository runRepository,
		DetectionIdGenerator idGenerator,
		TeacherTenantDatabaseContext tenantDatabaseContext
	) {
		this.runRepository = runRepository;
		this.idGenerator = idGenerator;
		this.tenantDatabaseContext = tenantDatabaseContext;
	}

	@Transactional
	public StartedDetectionAttempt start(
		UUID teacherId,
		String tenantAlias,
		UUID runId,
		Instant requestedAt
	) {
		tenantDatabaseContext.setCurrentTeacher(teacherId);
		DetectionRun run = findRun(teacherId, runId);
		String expectedKey = DetectionExecutionKey.daily(
			tenantAlias,
			run.analysisDate()
		).value();
		if (!run.idempotencyKey().equals(expectedKey)) {
			throw DetectionExecutionException.tenantMismatch();
		}
		UUID attemptId = idGenerator.nextIds(1).getFirst();
		String requestId = attemptId.toString();
		run.startAttempt(attemptId, requestId, requestedAt);

		return new StartedDetectionAttempt(
			attemptId,
			requestId,
			run.idempotencyKey(),
			run.snapshotPayload()
		);
	}

	@Transactional
	public void fail(
		UUID teacherId,
		UUID runId,
		UUID attemptId,
		Integer httpStatus,
		String errorCode,
		Instant completedAt
	) {
		tenantDatabaseContext.setCurrentTeacher(teacherId);
		DetectionRun run = findRun(teacherId, runId);
		run.markFailed(attemptId, httpStatus, errorCode, completedAt);
	}

	private DetectionRun findRun(UUID teacherId, UUID runId) {
		return runRepository.findByIdAndTeacherId(runId, teacherId)
			.orElseThrow(DetectionExecutionException::runNotFound);
	}

	public record StartedDetectionAttempt(
		UUID attemptId,
		String requestId,
		String idempotencyKey,
		String snapshotPayload
	) {
	}
}
