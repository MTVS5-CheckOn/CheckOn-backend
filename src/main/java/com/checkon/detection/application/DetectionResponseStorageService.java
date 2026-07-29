package com.checkon.detection.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionLifecycle;
import com.checkon.detection.domain.DetectionResultEvidenceDraft;
import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionSignalResult;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.infrastructure.persistence.DetectionSignalResultRepository;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DetectionResponseStorageService {

	private final DetectionRunRepository runRepository;
	private final DetectionSignalResultRepository signalResultRepository;
	private final DetectionIdGenerator idGenerator;
	private final ObjectMapper objectMapper;

	public DetectionResponseStorageService(
		DetectionRunRepository runRepository,
		DetectionSignalResultRepository signalResultRepository,
		DetectionIdGenerator idGenerator,
		ObjectMapper objectMapper
	) {
		this.runRepository = runRepository;
		this.signalResultRepository = signalResultRepository;
		this.idGenerator = idGenerator;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void storeSuccessfulResponse(
		UUID teacherId,
		UUID runId,
		UUID attemptId,
		int httpStatus,
		AiDetectionResponse response,
		Instant completedAt
	) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(runId, "runId must not be null");
		Objects.requireNonNull(attemptId, "attemptId must not be null");
		Objects.requireNonNull(response, "response must not be null");
		Objects.requireNonNull(completedAt, "completedAt must not be null");

		validateSuccessfulResponse(response);
		DetectionRun run = runRepository.findByIdAndTeacherId(runId, teacherId)
			.orElseThrow(() -> new DetectionResponseStorageException(
				"Detection run was not found inside the teacher boundary"
			));

		int evidenceCount = response.data().signals().stream()
			.mapToInt(signal -> signal.evidence().size())
			.sum();
		Iterator<UUID> ids = idGenerator
			.nextIds(response.data().signals().size() + evidenceCount)
			.iterator();

		List<DetectionSignalResult> results = response.data().signals().stream()
			.map(signal -> toDomain(runId, signal, ids, completedAt))
			.toList();
		signalResultRepository.saveAll(results);

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
				item.summary()
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
			BigDecimal.valueOf(signal.score()),
			signal.rank(),
			toLifecycle(signal.lifecycle()),
			signal.brief().text(),
			signal.brief().gatePassed(),
			signal.brief().fallbackUsed(),
			evidence,
			createdAt
		);
	}

	private void validateSuccessfulResponse(AiDetectionResponse response) {
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
		for (AiDetectionResponse.Signal signal : response.data().signals()) {
			if (signal == null || signal.brief() == null || signal.evidence() == null) {
				throw new DetectionResponseStorageException(
					"Every AI signal must contain brief and evidence"
				);
			}
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
}
