package com.checkon.detection.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

@Service
public class DetectionRunQueryService {

	private final DetectionRunRepository runRepository;
	private final TeacherTenantDatabaseContext tenantContext;

	public DetectionRunQueryService(
		DetectionRunRepository runRepository,
		TeacherTenantDatabaseContext tenantContext
	) {
		this.runRepository = runRepository;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public DetectionRunView find(UUID teacherId, UUID runId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(runId, "runId must not be null");
		tenantContext.setCurrentTeacher(teacherId);
		DetectionRun run = runRepository.findByIdAndTeacherId(runId, teacherId)
			.orElseThrow(DetectionExecutionException::runNotFound);
		return new DetectionRunView(
			run.id(),
			run.status(),
			run.analysisDate(),
			run.attempts().size(),
			run.errorCode()
		);
	}

	public record DetectionRunView(
		UUID runId,
		DetectionRunStatus status,
		LocalDate analysisDate,
		int attemptCount,
		String errorCode
	) {
	}
}
