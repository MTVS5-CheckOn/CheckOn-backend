package com.checkon.detection.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.integration.ai.AiDetectionSnapshotHasher;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PrepareDetectionRunService {

	private final DetectionRunRepository runRepository;
	private final DetectionIdGenerator idGenerator;
	private final AiDetectionSnapshotHasher snapshotHasher;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final TeacherTenantDatabaseContext tenantDatabaseContext;

	public PrepareDetectionRunService(
		DetectionRunRepository runRepository,
		DetectionIdGenerator idGenerator,
		AiDetectionSnapshotHasher snapshotHasher,
		ObjectMapper objectMapper,
		Clock clock,
		TeacherTenantDatabaseContext tenantDatabaseContext
	) {
		this.runRepository = runRepository;
		this.idGenerator = idGenerator;
		this.snapshotHasher = snapshotHasher;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.tenantDatabaseContext = tenantDatabaseContext;
	}

	@Transactional
	public PreparedDetectionRun prepare(
		UUID teacherId,
		String tenantAlias,
		LocalDate analysisDate,
		AiDetectionRequest request
	) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(analysisDate, "analysisDate must not be null");
		Objects.requireNonNull(request, "request must not be null");
		tenantDatabaseContext.setCurrentTeacher(teacherId);
		if (tenantAlias == null || tenantAlias.isBlank()) {
			throw new IllegalArgumentException("tenantAlias must not be blank");
		}
		if (request.snapshotMeta() == null
			|| request.snapshotMeta().weekStart() == null) {
			throw new IllegalArgumentException(
				"snapshot_meta.week_start must not be null"
			);
		}

		String snapshotHash = snapshotHasher.hash(request);
		AiDetectionRequest requestWithHash = withSnapshotHash(request, snapshotHash);
		String snapshotPayload = writeRequest(requestWithHash);
		DetectionExecutionKey executionKey =
			DetectionExecutionKey.daily(tenantAlias, analysisDate);

		return runRepository.findByTeacherIdAndAnalysisDate(teacherId, analysisDate)
			.map(existing -> existingResult(
				existing,
				snapshotHash,
				executionKey.value()
			))
			.orElseGet(() -> createRun(
				teacherId,
				analysisDate,
				requestWithHash.snapshotMeta().weekStart(),
				snapshotHash,
				snapshotPayload,
				executionKey.value()
			));
	}

	private PreparedDetectionRun createRun(
		UUID teacherId,
		LocalDate analysisDate,
		LocalDate weekStart,
		String snapshotHash,
		String snapshotPayload,
		String idempotencyKey
	) {
		UUID runId = idGenerator.nextIds(1).getFirst();
		DetectionRun run = DetectionRun.prepare(
			runId,
			teacherId,
			analysisDate,
			weekStart,
			idempotencyKey,
			snapshotHash,
			snapshotPayload,
			Instant.now(clock)
		);
		runRepository.save(run);
		return toResult(run, true);
	}

	private PreparedDetectionRun existingResult(
		DetectionRun existing,
		String snapshotHash,
		String idempotencyKey
	) {
		if (!existing.snapshotHash().equals(snapshotHash)
			|| !hasCurrentOrLegacyIdempotencyKey(existing, idempotencyKey)) {
			throw new PrepareDetectionRunException(
				"A different snapshot already exists for this teacher and analysis date"
			);
		}
		return toResult(existing, false);
	}

	private boolean hasCurrentOrLegacyIdempotencyKey(
		DetectionRun run,
		String currentKey
	) {
		if (run.idempotencyKey().equals(currentKey)) return true;
		String legacyKey = DetectionExecutionKey.daily(
			DetectionTenantKey.fromTeacherProfileId(run.teacherId()).value(),
			run.analysisDate()
		).value();
		return run.idempotencyKey().equals(legacyKey);
	}

	private PreparedDetectionRun toResult(DetectionRun run, boolean created) {
		return new PreparedDetectionRun(
			run.id(),
			run.status(),
			run.analysisDate(),
			run.weekStart(),
			run.snapshotHash(),
			run.idempotencyKey(),
			created
		);
	}

	private AiDetectionRequest withSnapshotHash(
		AiDetectionRequest request,
		String snapshotHash
	) {
		AiDetectionRequest.SnapshotMeta meta = request.snapshotMeta();
		return new AiDetectionRequest(
			new AiDetectionRequest.SnapshotMeta(
				meta.weekStart(),
				snapshotHash,
				meta.termContext(),
				meta.classes()
			),
			request.students(),
			request.learningEvents(),
			request.alertContext(),
			request.detectionEvidence()
		);
	}

	private String writeRequest(AiDetectionRequest request) {
		try {
			return objectMapper.writeValueAsString(request);
		}
		catch (JacksonException exception) {
			throw new PrepareDetectionRunException(
				"Detection snapshot could not be serialized"
			);
		}
	}

	public record PreparedDetectionRun(
		UUID runId,
		DetectionRunStatus status,
		LocalDate analysisDate,
		LocalDate weekStart,
		String snapshotHash,
		String idempotencyKey,
		boolean created
	) {
	}
}
