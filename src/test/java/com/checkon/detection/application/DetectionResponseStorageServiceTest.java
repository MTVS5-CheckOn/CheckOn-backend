package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.detection.domain.DetectionRequestAttemptStatus;
import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.domain.DetectionSignalResult;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.infrastructure.persistence.DetectionSignalResultRepository;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
class DetectionResponseStorageServiceTest {

	private static final UUID TEACHER_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000002");
	private static final String SNAPSHOT_HASH =
		"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	private final DetectionResponseStorageService storageService;
	private final DetectionRunRepository runRepository;
	private final DetectionSignalResultRepository signalResultRepository;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;
	private final JdbcTemplate jdbcTemplate;

	@Autowired
	DetectionResponseStorageServiceTest(
		DetectionResponseStorageService storageService,
		DetectionRunRepository runRepository,
		DetectionSignalResultRepository signalResultRepository,
		ObjectMapper objectMapper,
		TransactionTemplate transactionTemplate,
		JdbcTemplate jdbcTemplate
	) {
		this.storageService = storageService;
		this.runRepository = runRepository;
		this.signalResultRepository = signalResultRepository;
		this.objectMapper = objectMapper;
		this.transactionTemplate = transactionTemplate;
		this.jdbcTemplate = jdbcTemplate;
	}

	@BeforeEach
	void createTeacherFixture() {
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Test
	void storesAiSignalsAndMarksTheRunAndAttemptSucceeded() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000101");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000102");
		prepareRequestedRun(runId, attemptId, LocalDate.of(2026, 7, 28));

		storageService.storeSuccessfulResponse(
			TEACHER_ID,
			runId,
			attemptId,
			200,
			readDemoResponse(),
			Instant.parse("2026-07-27T17:10:03Z")
		);

		DetectionRun reloaded = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		List<DetectionSignalResult> results =
			signalResultRepository.findAllByDetectionRunIdOrderByClassRefAscRankAsc(
				runId
			);

		assertThat(reloaded.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(reloaded.aiExecutionId())
			.isEqualTo("4a216cfb-f0b1-4efa-91a3-b8a34cd8e112");
		assertThat(reloaded.aiVersionsPayload()).contains("\"pipeline\":\"0.1.0\"");
		assertThat(reloaded.responseStatsPayload())
			.contains("\"students_evaluated\":9");
		assertThat(reloaded.attempts()).singleElement().satisfies(attempt -> {
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.SUCCEEDED);
			assertThat(attempt.httpStatus()).isEqualTo(200);
		});
		assertThat(results).hasSize(2);
		assertThat(results).allSatisfy(result -> {
			assertThat(result.id().version()).isEqualTo(7);
			assertThat(result.evidence()).singleElement().satisfies(evidence ->
				assertThat(evidence.id().version()).isEqualTo(7)
			);
		});
	}

	@Test
	void rollsBackSignalRowsWhenRunSuccessTransitionFails() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000201");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000202");
		prepareRequestedRun(runId, attemptId, LocalDate.of(2026, 7, 29));

		assertThatThrownBy(() -> storageService.storeSuccessfulResponse(
			TEACHER_ID,
			runId,
			UUID.fromString("019846dc-7c00-7000-8000-000000000299"),
			200,
			readDemoResponse(),
			Instant.parse("2026-07-27T17:10:03Z")
		)).isInstanceOf(IllegalArgumentException.class);

		assertThat(signalResultRepository
			.findAllByDetectionRunIdOrderByClassRefAscRankAsc(runId))
			.isEmpty();
		DetectionRun reloaded = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(reloaded.status()).isEqualTo(DetectionRunStatus.REQUESTED);
		assertThat(reloaded.attempts()).singleElement().satisfies(attempt ->
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.REQUESTED)
		);
	}

	private void prepareRequestedRun(
		UUID runId,
		UUID attemptId,
		LocalDate analysisDate
	) {
		transactionTemplate.executeWithoutResult(status -> {
			DetectionRun run = DetectionRun.prepare(
				runId,
				TEACHER_ID,
				analysisDate,
				LocalDate.of(2026, 7, 20),
				"tn_demo_teacher:" + runId,
				SNAPSHOT_HASH,
				"{\"snapshot_meta\":{\"week_start\":\"2026-07-20\"}}",
				Instant.parse("2026-07-27T17:09:59Z")
			);
			run.startAttempt(
				attemptId,
				"request-" + attemptId,
				Instant.parse("2026-07-27T17:10:00Z")
			);
			runRepository.save(run);
		});
	}

	private AiDetectionResponse readDemoResponse() throws IOException {
		try (var input = new ClassPathResource(
			"ai/detect-contract-response.json"
		).getInputStream()) {
			return objectMapper.readValue(input, AiDetectionResponse.class);
		}
	}
}
