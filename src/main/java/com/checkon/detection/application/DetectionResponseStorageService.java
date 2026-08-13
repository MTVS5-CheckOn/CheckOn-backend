package com.checkon.detection.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionLifecycle;
import com.checkon.detection.domain.DetectionResultEvidenceDraft;
import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionSignalResult;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.infrastructure.persistence.DetectionSignalResultRepository;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.engagement.application.EngagementCandidateService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DetectionResponseStorageService {

	private final DetectionRunRepository runRepository;
	private final DetectionSignalResultRepository signalResultRepository;
	private final DetectionIdGenerator idGenerator;
	private final ObjectMapper objectMapper;
	private final TeacherTenantDatabaseContext tenantDatabaseContext;
	private final EngagementCandidateService engagementCandidateService;

	public DetectionResponseStorageService(
		DetectionRunRepository runRepository,
		DetectionSignalResultRepository signalResultRepository,
		DetectionIdGenerator idGenerator,
		ObjectMapper objectMapper,
		TeacherTenantDatabaseContext tenantDatabaseContext,
		EngagementCandidateService engagementCandidateService
	) {
		this.runRepository = runRepository;
		this.signalResultRepository = signalResultRepository;
		this.idGenerator = idGenerator;
		this.objectMapper = objectMapper;
		this.tenantDatabaseContext = tenantDatabaseContext;
		this.engagementCandidateService = engagementCandidateService;
	}

	@Transactional
	public void storeSuccessfulResponse(
		UUID teacherId,
		UUID runId,
		UUID attemptId,
		Integer httpStatus,
		AiDetectionResponse response,
		Instant completedAt
	) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(runId, "runId must not be null");
		Objects.requireNonNull(attemptId, "attemptId must not be null");
		Objects.requireNonNull(response, "response must not be null");
		Objects.requireNonNull(completedAt, "completedAt must not be null");
		tenantDatabaseContext.setCurrentTeacher(teacherId);

		DetectionRun run = runRepository.findByIdAndTeacherId(runId, teacherId)
			.orElseThrow(() -> new DetectionResponseStorageException(
				"Detection run was not found inside the teacher boundary"
			));
		AiDetectionRequest request = readRequest(run.snapshotPayload());

		// AI 결과를 행 단위로 저장하면서 검증하면 뒤쪽 signal의 오류 때문에
		// 앞쪽 결과만 남을 수 있다. 요청 snapshot과 응답 전체를 먼저 대조한 뒤
		// 하나의 트랜잭션에서만 저장해 근거 없는 위험 신호를 차단한다.
		validateSuccessfulResponse(response, request);

		int evidenceCount = response.data().signals().stream()
			.mapToInt(signal -> signal.evidence().size())
			.sum();
		Iterator<UUID> ids = idGenerator
			.nextIds(response.data().signals().size() + evidenceCount)
			.iterator();

		List<DetectionSignalResult> results = response.data().signals().stream()
			.map(signal -> toDomain(runId, signal, ids, completedAt))
			.toList();
		signalResultRepository.saveAllAndFlush(results);
		engagementCandidateService.createPendingAlerts(teacherId, runId, completedAt);

		run.markSucceeded(
			attemptId,
			response.meta().executionId(),
			httpStatus,
			completedAt,
			writeJson(response.meta().versions(), "AI versions"),
			writeJson(response.data().stats(), "AI response stats")
		);
	}

	private DetectionSignalResult toDomain(
		UUID runId,
		AiDetectionResponse.Signal signal,
		Iterator<UUID> ids,
		Instant createdAt
	) {
		List<DetectionResultEvidenceDraft> evidence = new ArrayList<>();
		for (AiDetectionResponse.Evidence item : signal.evidence()) {
			evidence.add(new DetectionResultEvidenceDraft(
				nextId(ids),
				item.sourceTable(),
				item.recordId(),
				item.summary(),
				item.role(),
				item.observed(),
				item.sampleSize(),
				item.occurredOn()
			));
		}

		return DetectionSignalResult.create(
			nextId(ids),
			runId,
			signal.signalId(),
			signal.studentRef(),
			signal.classRef(),
			signal.ruleId(),
			signal.signalType(),
			signal.displayLabel(),
			signal.metric(),
			signal.observed(),
			signal.baseline(),
			signal.sampleSize(),
			BigDecimal.valueOf(signal.score()),
			signal.rank(),
			Boolean.TRUE.equals(signal.advisory()),
			toLifecycle(signal.lifecycle()),
			signal.brief().text(),
			signal.brief().gatePassed(),
			signal.brief().fallbackUsed(),
			evidence,
			createdAt
		);
	}

	private void validateSuccessfulResponse(
		AiDetectionResponse response,
		AiDetectionRequest request
	) {
		if (response.error() != null) {
			throw new DetectionResponseStorageException(
				"Successful AI response must not contain an error"
			);
		}
		if (response.data() == null || response.data().signals() == null) {
			throw new DetectionResponseStorageException(
				"Successful AI response must contain signals"
			);
		}
		if (response.data().stats() == null) {
			throw new DetectionResponseStorageException(
				"Successful AI response must contain stats"
			);
		}
		if (response.meta() == null || isBlank(response.meta().executionId())) {
			throw new DetectionResponseStorageException(
				"Successful AI response must contain execution_id"
			);
		}
		if (response.meta().versions() == null) {
			throw new DetectionResponseStorageException(
				"Successful AI response must contain versions"
			);
		}
		Map<String, String> requestedStudentClasses = request.students().stream()
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				AiDetectionRequest.StudentSnapshot::studentRef,
				AiDetectionRequest.StudentSnapshot::classRef
			));
		Map<String, String> requestedLearningRecordStudents = request.learningEvents().stream()
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				AiDetectionRequest.LearningEventSnapshot::recordId,
				AiDetectionRequest.LearningEventSnapshot::studentRef
			));
		Map<EvidenceReference, String> requestedEvidenceStudents = new java.util.HashMap<>();
		for (AiDetectionRequest.LearningEventSnapshot event : request.learningEvents()) {
			requestedEvidenceStudents.put(
				new EvidenceReference("learning_records", event.recordId()), event.studentRef()
			);
		}
		for (AiDetectionRequest.DetectionEvidence evidence : request.detectionEvidence()) {
			requestedEvidenceStudents.put(
				new EvidenceReference(evidence.sourceTable(), evidence.recordId()),
				evidence.studentRef()
			);
		}
		Set<String> signalIds = new HashSet<>();
		for (AiDetectionResponse.Signal signal : response.data().signals()) {
			if (signal == null || signal.brief() == null || signal.evidence() == null) {
				throw new DetectionResponseStorageException(
					"Every AI signal must contain brief and evidence"
				);
			}
			if (signal.evidence().isEmpty()) {
				throw new DetectionResponseStorageException(
					"Every AI signal must contain at least one evidence"
				);
			}
			if (isBlank(signal.signalId()) || !signalIds.add(signal.signalId())) {
				throw new DetectionResponseStorageException(
					"AI signal_id must be present and unique inside a response"
				);
			}
			if (isBlank(signal.studentRef())
				|| !requestedStudentClasses.containsKey(signal.studentRef())) {
				throw new DetectionResponseStorageException(
					"AI signal student_ref must belong to the request snapshot"
				);
			}
			if (!Objects.equals(
				requestedStudentClasses.get(signal.studentRef()),
				signal.classRef()
			)) {
				throw new DetectionResponseStorageException(
					"AI signal class_ref must match the requested student snapshot"
				);
			}
			if (isBlank(signal.brief().text())) {
				throw new DetectionResponseStorageException(
					"AI signal brief text must not be blank"
				);
			}
			if (!Double.isFinite(signal.score())
				|| signal.score() < 0 || signal.score() > 1) {
				throw new DetectionResponseStorageException(
					"AI signal score must be between 0 and 1"
				);
			}
			if (signal.rank() < 1) {
				throw new DetectionResponseStorageException(
					"AI signal rank must be at least 1"
				);
			}
			validateStructuredSignal(signal);
			toLifecycle(signal.lifecycle());
			Set<EvidenceReference> evidenceReferences = new HashSet<>();
			int triggerEvidenceCount = 0;
			int baselineEvidenceCount = 0;
			for (AiDetectionResponse.Evidence evidence : signal.evidence()) {
				if (evidence == null || isBlank(evidence.recordId())
					|| isBlank(evidence.sourceTable())) {
					throw new DetectionResponseStorageException(
						"AI evidence source_table and record_id must belong to the request snapshot"
					);
				}
				EvidenceReference reference = new EvidenceReference(
					evidence.sourceTable(), evidence.recordId()
				);
				// Earlier AI contract versions used learning_event as the logical
				// source name. Keep those learning-record results readable while
				// requiring an exact (source_table, record_id) pair for the new
				// absence and return evidence.
				String evidenceStudentRef = requestedEvidenceStudents.get(reference);
				if (evidenceStudentRef == null) {
					evidenceStudentRef = requestedLearningRecordStudents.get(evidence.recordId());
				}
				if (!Objects.equals(signal.studentRef(), evidenceStudentRef)) {
					throw new DetectionResponseStorageException(
						"AI evidence must belong to the signal student inside the request snapshot"
					);
				}
				if (!evidenceReferences.add(reference)) {
					throw new DetectionResponseStorageException(
						"AI evidence source_table and record_id must be unique inside a signal"
					);
				}
				if (isBlank(evidence.summary())) {
					throw new DetectionResponseStorageException(
						"AI evidence source and summary must not be blank"
					);
				}
				String role = validateStructuredEvidence(signal.ruleId(), evidence);
				if ("trigger".equals(role)) {
					triggerEvidenceCount++;
				}
				else if ("baseline".equals(role)) {
					baselineEvidenceCount++;
				}
			}
			if ("R3".equals(signal.ruleId())
				&& (triggerEvidenceCount > 3 || baselineEvidenceCount > 3
					|| triggerEvidenceCount + baselineEvidenceCount > 6)) {
				throw new DetectionResponseStorageException(
					"R3 evidence must contain at most 3 trigger and 3 baseline records"
				);
			}
		}
	}

	private void validateStructuredSignal(AiDetectionResponse.Signal signal) {
		if (isBlank(signal.ruleId())) {
			throw new DetectionResponseStorageException(
				"AI signal rule_id must not be blank"
			);
		}
		if (signal.sampleSize() != null && signal.sampleSize() < 0) {
			throw new DetectionResponseStorageException(
				"AI signal sample_size must not be negative"
			);
		}
		if (signal.metric() != null && signal.metric().isBlank()) {
			throw new DetectionResponseStorageException(
				"AI signal metric must not be blank when present"
			);
		}
		String expectedMetric = switch (signal.ruleId()) {
			case "R1" -> "accuracy";
			case "R2" -> "consecutive_missing_weeks";
			case "R3" -> "activity_count";
			case "R4" -> "norm_time";
			case "R5" -> null;
			case "R6" -> "error_share";
			default -> signal.metric();
		};
		if (signal.metric() != null && !Objects.equals(signal.metric(), expectedMetric)) {
			throw new DetectionResponseStorageException(
				"AI signal metric does not match rule_id"
			);
		}
		if (signal.baseline() != null
			&& !Set.of("R1", "R3", "R4").contains(signal.ruleId())) {
			throw new DetectionResponseStorageException(
				"AI signal baseline is not supported for this rule_id"
			);
		}
	}

	private String validateStructuredEvidence(
		String ruleId,
		AiDetectionResponse.Evidence evidence
	) {
		if (evidence.sampleSize() != null && evidence.sampleSize() < 0) {
			throw new DetectionResponseStorageException(
				"AI evidence sample_size must not be negative"
			);
		}
		boolean hasStructuredValue = evidence.role() != null
			|| evidence.observed() != null
			|| evidence.sampleSize() != null
			|| evidence.occurredOn() != null;
		if (!hasStructuredValue) {
			return null;
		}
		if (!"trigger".equals(evidence.role()) && !"baseline".equals(evidence.role())) {
			throw new DetectionResponseStorageException(
				"Structured AI evidence role must be trigger or baseline"
			);
		}
		if ("baseline".equals(evidence.role()) && !"R3".equals(ruleId)) {
			throw new DetectionResponseStorageException(
				"Baseline evidence records are supported only for R3"
			);
		}
		return evidence.role();
	}

	private AiDetectionRequest readRequest(String snapshotPayload) {
		try {
			AiDetectionRequest request = objectMapper.readValue(
				snapshotPayload,
				AiDetectionRequest.class
			);
			if (request.students() == null || request.learningEvents() == null) {
				throw new DetectionResponseStorageException(
					"Stored detection snapshot is missing students or learning events"
				);
			}
			return request;
		}
		catch (JacksonException exception) {
			throw new DetectionResponseStorageException(
				"Stored detection snapshot could not be read",
				exception
			);
		}
	}

	private DetectionLifecycle toLifecycle(String lifecycle) {
		if (isBlank(lifecycle)) {
			throw new DetectionResponseStorageException(
				"AI lifecycle must not be blank"
			);
		}
		try {
			return DetectionLifecycle.valueOf(lifecycle.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			throw new DetectionResponseStorageException(
				"Unsupported AI lifecycle: " + lifecycle,
				exception
			);
		}
	}

	private String writeJson(Object value, String description) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new DetectionResponseStorageException(
				"Failed to serialize " + description,
				exception
			);
		}
	}

	private UUID nextId(Iterator<UUID> ids) {
		if (!ids.hasNext()) {
			throw new DetectionResponseStorageException(
				"Detection ID generator returned fewer IDs than requested"
			);
		}
		return ids.next();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record EvidenceReference(String sourceTable, String recordId) {
	}
}
