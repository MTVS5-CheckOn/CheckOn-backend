package com.checkon.detection.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.detection.integration.ai.AiDetectionRequestHeaders;
import com.checkon.detection.integration.ai.RiskDetectionClient;
import com.checkon.detection.integration.ai.RiskDetectionClientException;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RiskDetectionExecutionService {

	private static final int SUCCESS_HTTP_STATUS = 200;

	private final DetectionAttemptCoordinator attemptCoordinator;
	private final DetectionResponseStorageService responseStorageService;
	private final RiskDetectionClient riskDetectionClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public RiskDetectionExecutionService(
		DetectionAttemptCoordinator attemptCoordinator,
		DetectionResponseStorageService responseStorageService,
		RiskDetectionClient riskDetectionClient,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.attemptCoordinator = attemptCoordinator;
		this.responseStorageService = responseStorageService;
		this.riskDetectionClient = riskDetectionClient;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public void execute(UUID teacherId, String tenantAlias, UUID runId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(runId, "runId must not be null");
		if (tenantAlias == null || tenantAlias.isBlank()) {
			throw new IllegalArgumentException("tenantAlias must not be blank");
		}

		DetectionAttemptCoordinator.StartedDetectionAttempt attempt =
			attemptCoordinator.start(teacherId, runId, Instant.now(clock));

		try {
			AiDetectionRequest request = readRequest(attempt.snapshotPayload());
			AiDetectionResponse response = riskDetectionClient.detect(
				request,
				new AiDetectionRequestHeaders(
					tenantAlias,
					attempt.requestId(),
					new DetectionExecutionKey(attempt.idempotencyKey())
				)
			);
			responseStorageService.storeSuccessfulResponse(
				teacherId,
				runId,
				attempt.attemptId(),
				SUCCESS_HTTP_STATUS,
				response,
				Instant.now(clock)
			);
		}
		catch (RiskDetectionClientException exception) {
			markFailed(
				teacherId,
				runId,
				attempt.attemptId(),
				exception.httpStatus(),
				exception.reason().name()
			);
			throw exception;
		}
		catch (DetectionExecutionException exception) {
			markFailed(
				teacherId,
				runId,
				attempt.attemptId(),
				null,
				"SNAPSHOT_PAYLOAD_INVALID"
			);
			throw exception;
		}
		catch (RuntimeException exception) {
			markFailed(
				teacherId,
				runId,
				attempt.attemptId(),
				null,
				"RESPONSE_PROCESSING_ERROR"
			);
			throw new DetectionExecutionException(
				"AI detection response could not be processed",
				exception
			);
		}
	}

	private AiDetectionRequest readRequest(String snapshotPayload) {
		try {
			return objectMapper.readValue(snapshotPayload, AiDetectionRequest.class);
		}
		catch (JacksonException exception) {
			throw new DetectionExecutionException(
				"Stored detection snapshot payload is invalid",
				exception
			);
		}
	}

	private void markFailed(
		UUID teacherId,
		UUID runId,
		UUID attemptId,
		Integer httpStatus,
		String errorCode
	) {
		attemptCoordinator.fail(
			teacherId,
			runId,
			attemptId,
			httpStatus,
			errorCode,
			Instant.now(clock)
		);
	}
}
