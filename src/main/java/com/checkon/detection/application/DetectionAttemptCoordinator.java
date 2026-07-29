package com.checkon.detection.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;

@Service
public class DetectionAttemptCoordinator {

	private final DetectionRunRepository runRepository;
	private final DetectionIdGenerator idGenerator;

	public DetectionAttemptCoordinator(
		DetectionRunRepository runRepository,
		DetectionIdGenerator idGenerator
	) {
		this.runRepository = runRepository;
		this.idGenerator = idGenerator;
	}

	@Transactional
	public StartedDetectionAttempt start(
		UUID teacherId,
		UUID runId,
		Instant requestedAt
	) {
		DetectionRun run = findRun(teacherId, runId);
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
		DetectionRun run = findRun(teacherId, runId);
		run.markFailed(attemptId, httpStatus, errorCode, completedAt);
	}

	private DetectionRun findRun(UUID teacherId, UUID runId) {
		return runRepository.findByIdAndTeacherId(runId, teacherId)
			.orElseThrow(() -> new DetectionExecutionException(
				"Detection run was not found inside the teacher boundary"
			));
	}

	public record StartedDetectionAttempt(
		UUID attemptId,
		String requestId,
		String idempotencyKey,
		String snapshotPayload
	) {
	}
}
