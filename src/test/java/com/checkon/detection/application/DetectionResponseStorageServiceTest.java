package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
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
	@DisplayName("Given R1 진단 stats가 있는 AI 응답, When 저장하면, Then 진단 필드와 성공 상태를 보존한다")
	void givenR1DiagnosticStats_whenStoringResponse_thenPreservesStatsAndSuccess() throws IOException {
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
			signalResultRepository
				.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			);

		assertThat(reloaded.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(reloaded.aiExecutionId())
			.isEqualTo("4a216cfb-f0b1-4efa-91a3-b8a34cd8e112");
		assertThat(reloaded.aiVersionsPayload()).contains("\"pipeline\":\"0.1.0\"");
		assertThat(reloaded.responseStatsPayload())
			.contains("\"students_evaluated\":9")
			.contains("\"r1_threshold_pp\":12.50")
			.contains("\"r1_threshold_source\":\"pooled\"")
			.contains("\"r1_pool_n\":42");
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
		assertThat(results).filteredOn(result -> result.ruleId().equals("R1"))
			.singleElement().satisfies(result -> {
				assertThat(result.metric()).isEqualTo("accuracy");
				assertThat(result.observed()).isEqualByComparingTo("0.65");
				assertThat(result.baseline()).isEqualByComparingTo("0.82");
				assertThat(result.sampleSize()).isEqualTo(20);
				assertThat(result.evidence()).singleElement().satisfies(evidence -> {
					assertThat(evidence.role()).isEqualTo("trigger");
					assertThat(evidence.observed()).isEqualByComparingTo("0.65");
					assertThat(evidence.sampleSize()).isEqualTo(20);
					assertThat(evidence.occurredOn()).isEqualTo(LocalDate.of(2026, 7, 20));
				});
			});
	}

	@Test
	@DisplayName("Given R1 진단 stats가 없는 기존 AI 응답, When 저장하면, Then nullable 필드로 성공 처리한다")
	void givenLegacyStatsWithoutR1Diagnostics_whenStoringResponse_thenRemainsCompatible()
		throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000281");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000282");
		prepareRequestedRun(runId, attemptId, LocalDate.of(2026, 8, 6));
		AiDetectionResponse base = readDemoResponse();
		AiDetectionResponse legacyResponse = new AiDetectionResponse(
			new AiDetectionResponse.Data(
				List.of(), new AiDetectionResponse.Stats(1, 0, 0, 0, List.of())
			),
			null,
			base.meta()
		);

		storageService.storeSuccessfulResponse(
			TEACHER_ID, runId, attemptId, 200, legacyResponse,
			Instant.parse("2026-07-27T17:10:03Z")
		);

		DetectionRun reloaded = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(reloaded.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(reloaded.responseStatsPayload())
			.contains("\"students_evaluated\":1")
			.contains("\"r1_threshold_pp\":null")
			.contains("\"r1_threshold_source\":null")
			.contains("\"r1_pool_n\":null");
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
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			))
			.isEmpty();
		DetectionRun reloaded = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(reloaded.status()).isEqualTo(DetectionRunStatus.REQUESTED);
		assertThat(reloaded.attempts()).singleElement().satisfies(attempt ->
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.REQUESTED)
		);
	}

	@Test
	void rejectsStudentAndEvidenceReferencesOutsideTheStoredSnapshot()
		throws IOException {
		UUID unknownStudentRun =
			UUID.fromString("019846dc-7c00-7000-8000-000000000211");
		UUID unknownStudentAttempt =
			UUID.fromString("019846dc-7c00-7000-8000-000000000212");
		prepareRequestedRun(
			unknownStudentRun,
			unknownStudentAttempt,
			LocalDate.of(2026, 7, 30)
		);
		AiDetectionResponse response = readDemoResponse();
		AiDetectionResponse.Signal first = response.data().signals().getFirst();
		AiDetectionResponse unknownStudent = withSignals(
			response,
			List.of(copySignal(first, "st_not_requested", first.evidence()))
		);

		assertRejectedWithoutPartialRows(
			unknownStudentRun,
			unknownStudentAttempt,
			unknownStudent
		);

		UUID unknownRecordRun =
			UUID.fromString("019846dc-7c00-7000-8000-000000000221");
		UUID unknownRecordAttempt =
			UUID.fromString("019846dc-7c00-7000-8000-000000000222");
		prepareRequestedRun(
			unknownRecordRun,
			unknownRecordAttempt,
			LocalDate.of(2026, 7, 31)
		);
		AiDetectionResponse.Evidence evidence = first.evidence().getFirst();
		AiDetectionResponse unknownRecord = withSignals(
			response,
			List.of(copySignal(
				first,
				first.studentRef(),
				List.of(new AiDetectionResponse.Evidence(
					evidence.sourceTable(),
					"le_not_requested",
					evidence.summary()
				))
			))
		);

		assertRejectedWithoutPartialRows(
			unknownRecordRun,
			unknownRecordAttempt,
			unknownRecord
		);
	}

	@Test
	void rejectsDuplicateSignalAndEvidenceIdentifiersBeforeSaving()
		throws IOException {
		UUID duplicateSignalRun =
			UUID.fromString("019846dc-7c00-7000-8000-000000000231");
		UUID duplicateSignalAttempt =
			UUID.fromString("019846dc-7c00-7000-8000-000000000232");
		prepareRequestedRun(
			duplicateSignalRun,
			duplicateSignalAttempt,
			LocalDate.of(2026, 8, 1)
		);
		AiDetectionResponse response = readDemoResponse();
		AiDetectionResponse.Signal first = response.data().signals().getFirst();

		assertRejectedWithoutPartialRows(
			duplicateSignalRun,
			duplicateSignalAttempt,
			withSignals(response, List.of(first, first))
		);

		UUID duplicateEvidenceRun =
			UUID.fromString("019846dc-7c00-7000-8000-000000000241");
		UUID duplicateEvidenceAttempt =
			UUID.fromString("019846dc-7c00-7000-8000-000000000242");
		prepareRequestedRun(
			duplicateEvidenceRun,
			duplicateEvidenceAttempt,
			LocalDate.of(2026, 8, 2)
		);
		AiDetectionResponse.Evidence evidence = first.evidence().getFirst();
		AiDetectionResponse duplicateEvidence = withSignals(
			response,
			List.of(copySignal(
				first,
				first.studentRef(),
				List.of(evidence, evidence)
			))
		);

		assertRejectedWithoutPartialRows(
			duplicateEvidenceRun,
			duplicateEvidenceAttempt,
			duplicateEvidence
		);
	}

	@Test
	@DisplayName("Given a requested v0.2 evidence snapshot, When AI cites its exact pair, Then the result is stored")
	void givenEvidenceSnapshot_whenAiCitesExactPair_thenStoresResult() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000251");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000252");
		prepareRequestedRunWithEvidence(runId, attemptId, LocalDate.of(2026, 8, 3));
		AiDetectionResponse response = readDemoResponse();
		AiDetectionResponse.Signal first = response.data().signals().getFirst();
		AiDetectionResponse evidenceResponse = withSignals(response, List.of(copySignal(
			first,
			first.studentRef(),
			List.of(new AiDetectionResponse.Evidence(
				"assignment_week_summary",
				"assignment-summary:st_10:2026-07-20",
				"해당 주 과제 3건 중 0건 제출"
			))
		)));

		storageService.storeSuccessfulResponse(
			TEACHER_ID, runId, attemptId, 200, evidenceResponse,
			Instant.parse("2026-07-27T17:10:03Z")
		);

		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId, TEACHER_ID
			)).singleElement().satisfies(signal ->
			assertThat(signal.evidence()).singleElement().satisfies(evidence -> {
				assertThat(evidence.sourceHint()).isEqualTo("assignment_week_summary");
				assertThat(evidence.recordId()).isEqualTo(
					"assignment-summary:st_10:2026-07-20"
				);
			})
		);
	}

	@Test
	@DisplayName("Given advisory AI signal, When the response is stored, Then advisory value is retained")
	void givenAdvisorySignal_whenStored_thenRetainsAdvisory() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000271");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000272");
		prepareRequestedRun(runId, attemptId, LocalDate.of(2026, 8, 5));
		AiDetectionResponse response = readDemoResponse();
		AiDetectionResponse.Signal signal = response.data().signals().getFirst();
		AiDetectionResponse advisoryResponse = withSignals(response, List.of(
			new AiDetectionResponse.Signal(
				signal.signalId(), signal.studentRef(), signal.classRef(), signal.ruleId(),
				signal.signalType(), signal.displayLabel(), signal.score(), signal.rank(), true,
				signal.lifecycle(), signal.brief(), signal.evidence()
			)
		));

		storageService.storeSuccessfulResponse(
			TEACHER_ID, runId, attemptId, 200, advisoryResponse,
			Instant.parse("2026-07-27T17:10:03Z")
		);

		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId, TEACHER_ID
			)).singleElement().extracting(DetectionSignalResult::advisory).isEqualTo(true);
	}

	@Test
	@DisplayName("Given R1 baseline evidence row, When storing, Then the response is rejected atomically")
	void givenBaselineEvidenceForR1_whenStoring_thenRejectsWithoutPartialRows()
		throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000291");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000292");
		prepareRequestedRun(runId, attemptId, LocalDate.of(2026, 8, 7));
		AiDetectionResponse response = readDemoResponse();
		AiDetectionResponse.Signal first = response.data().signals().getFirst();
		AiDetectionResponse.Evidence source = first.evidence().getFirst();
		AiDetectionResponse invalid = withSignals(response, List.of(new AiDetectionResponse.Signal(
			first.signalId(), first.studentRef(), first.classRef(), first.ruleId(),
			first.signalType(), first.displayLabel(), first.metric(), first.observed(),
			first.baseline(), first.sampleSize(), first.score(), first.rank(), first.advisory(),
			first.lifecycle(), first.brief(), List.of(new AiDetectionResponse.Evidence(
				source.sourceTable(), source.recordId(), source.summary(), "baseline",
				source.observed(), source.sampleSize(), source.occurredOn()
			))
		)));

		assertRejectedWithoutPartialRows(runId, attemptId, invalid);
	}

	@Test
	@DisplayName("Given R3 with four trigger evidence rows, When storing, Then the response is rejected atomically")
	void givenR3WithFourTriggerEvidenceRows_whenStoring_thenRejectsWithoutPartialRows()
		throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000301");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000302");
		List<AiDetectionRequest.DetectionEvidence> requestEvidence = new java.util.ArrayList<>();
		List<AiDetectionResponse.Evidence> responseEvidence = new java.util.ArrayList<>();
		for (int index = 0; index < 4; index++) {
			LocalDate occurredOn = LocalDate.of(2026, 7, 20).plusWeeks(index);
			String recordId = "activity:st_10:" + occurredOn;
			requestEvidence.add(AiDetectionRequest.DetectionEvidence.weeklyActivity(
				"student_week_activity", recordId, "st_10", occurredOn, index
			));
			responseEvidence.add(new AiDetectionResponse.Evidence(
				"student_week_activity", recordId, "학습 활동 근거", "trigger",
				BigDecimal.valueOf(index), null, occurredOn
			));
		}
		prepareRequestedRunWithEvidence(
			runId, attemptId, LocalDate.of(2026, 8, 8), requestEvidence
		);
		AiDetectionResponse base = readDemoResponse();
		AiDetectionResponse.Signal source = base.data().signals().getFirst();
		AiDetectionResponse.Signal r3 = new AiDetectionResponse.Signal(
			source.signalId(), source.studentRef(), source.classRef(), "R3", "learning_gap",
			"학습 공백", "activity_count", BigDecimal.ZERO, BigDecimal.TEN, null,
			source.score(), source.rank(), source.advisory(), source.lifecycle(),
			source.brief(), responseEvidence
		);

		assertRejectedWithoutPartialRows(
			runId, attemptId, withSignals(base, List.of(r3))
		);
	}

	@Test
	@DisplayName("Given R3 with three trigger and three baseline rows, When storing, Then all six evidence rows are retained")
	void givenR3WithSixValidEvidenceRows_whenStoring_thenRetainsEveryRow()
		throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000311");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000312");
		List<AiDetectionRequest.DetectionEvidence> requestEvidence = new java.util.ArrayList<>();
		List<AiDetectionResponse.Evidence> responseEvidence = new java.util.ArrayList<>();
		for (int index = 0; index < 6; index++) {
			LocalDate occurredOn = LocalDate.of(2026, 6, 29).plusWeeks(index);
			String recordId = "activity:st_10:" + occurredOn;
			requestEvidence.add(AiDetectionRequest.DetectionEvidence.weeklyActivity(
				"student_week_activity", recordId, "st_10", occurredOn, index
			));
			responseEvidence.add(new AiDetectionResponse.Evidence(
				"student_week_activity", recordId, "학습 활동 근거",
				index < 3 ? "baseline" : "trigger", BigDecimal.valueOf(index), null,
				occurredOn
			));
		}
		prepareRequestedRunWithEvidence(
			runId, attemptId, LocalDate.of(2026, 8, 9), requestEvidence
		);
		AiDetectionResponse base = readDemoResponse();
		AiDetectionResponse.Signal source = base.data().signals().getFirst();
		AiDetectionResponse.Signal r3 = new AiDetectionResponse.Signal(
			source.signalId(), source.studentRef(), source.classRef(), "R3", "volume_gap",
			"학습 공백", "activity_count", BigDecimal.valueOf(2), BigDecimal.valueOf(8),
			3, source.score(), source.rank(), source.advisory(), source.lifecycle(),
			source.brief(), responseEvidence
		);

		storageService.storeSuccessfulResponse(
			TEACHER_ID, runId, attemptId, 200, withSignals(base, List.of(r3)),
			Instant.parse("2026-08-09T17:10:03Z")
		);

		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId, TEACHER_ID
			)).singleElement().satisfies(result -> {
				assertThat(result.evidence()).hasSize(6);
				assertThat(result.evidence()).extracting(evidence -> evidence.role())
					.containsExactlyInAnyOrder(
						"baseline", "baseline", "baseline", "trigger", "trigger", "trigger"
					);
			});
	}

	@Test
	@DisplayName("Given a requested v0.2 evidence snapshot, When AI changes source_table, Then the result is rejected")
	void givenEvidenceSnapshot_whenAiChangesSourceTable_thenRejectsResult() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000261");
		UUID attemptId = UUID.fromString("019846dc-7c00-7000-8000-000000000262");
		prepareRequestedRunWithEvidence(runId, attemptId, LocalDate.of(2026, 8, 4));
		AiDetectionResponse response = readDemoResponse();
		AiDetectionResponse.Signal first = response.data().signals().getFirst();
		AiDetectionResponse mismatchedSource = withSignals(response, List.of(copySignal(
			first,
			first.studentRef(),
			List.of(new AiDetectionResponse.Evidence(
				"student_week_activity",
				"assignment-summary:st_10:2026-07-20",
				"다른 logical source"
			))
		)));

		assertRejectedWithoutPartialRows(runId, attemptId, mismatchedSource);
	}

	private void prepareRequestedRun(
		UUID runId,
		UUID attemptId,
		LocalDate analysisDate
	) throws IOException {
		String snapshotPayload = new ClassPathResource(
			"ai/detect-contract-request.json"
		).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		transactionTemplate.executeWithoutResult(status -> {
			DetectionRun run = DetectionRun.prepare(
				runId,
				TEACHER_ID,
				analysisDate,
				LocalDate.of(2026, 7, 20),
				"tn_demo_teacher:" + runId,
				SNAPSHOT_HASH,
				snapshotPayload,
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

	private void prepareRequestedRunWithEvidence(
		UUID runId,
		UUID attemptId,
		LocalDate analysisDate
	) throws IOException {
		AiDetectionRequest base = objectMapper.readValue(new ClassPathResource(
			"ai/detect-contract-request.json"
		).getContentAsString(java.nio.charset.StandardCharsets.UTF_8), AiDetectionRequest.class);
		AiDetectionRequest snapshot = new AiDetectionRequest(
			base.snapshotMeta(), base.students(), base.learningEvents(), base.alertContext(),
			List.of(AiDetectionRequest.DetectionEvidence.assignmentWindow(
				"assignment_week_summary",
				"assignment-summary:st_10:2026-07-20",
				"st_10", LocalDate.of(2026, 7, 20), 3, 0
			))
		);
		String snapshotPayload = objectMapper.writeValueAsString(snapshot);
		transactionTemplate.executeWithoutResult(status -> {
			DetectionRun run = DetectionRun.prepare(
				runId, TEACHER_ID, analysisDate, LocalDate.of(2026, 7, 20),
				"tn_demo_teacher:" + runId, SNAPSHOT_HASH, snapshotPayload,
				Instant.parse("2026-07-27T17:09:59Z")
			);
			run.startAttempt(attemptId, "request-" + attemptId,
				Instant.parse("2026-07-27T17:10:00Z"));
			runRepository.save(run);
		});
	}

	private void prepareRequestedRunWithEvidence(
		UUID runId,
		UUID attemptId,
		LocalDate analysisDate,
		List<AiDetectionRequest.DetectionEvidence> detectionEvidence
	) throws IOException {
		AiDetectionRequest base = objectMapper.readValue(new ClassPathResource(
			"ai/detect-contract-request.json"
		).getContentAsString(java.nio.charset.StandardCharsets.UTF_8), AiDetectionRequest.class);
		AiDetectionRequest snapshot = new AiDetectionRequest(
			base.snapshotMeta(), base.students(), base.learningEvents(), base.alertContext(),
			detectionEvidence
		);
		String snapshotPayload = objectMapper.writeValueAsString(snapshot);
		transactionTemplate.executeWithoutResult(status -> {
			DetectionRun run = DetectionRun.prepare(
				runId, TEACHER_ID, analysisDate, LocalDate.of(2026, 7, 20),
				"tn_demo_teacher:" + runId, SNAPSHOT_HASH, snapshotPayload,
				Instant.parse("2026-07-27T17:09:59Z")
			);
			run.startAttempt(attemptId, "request-" + attemptId,
				Instant.parse("2026-07-27T17:10:00Z"));
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

	private void assertRejectedWithoutPartialRows(
		UUID runId,
		UUID attemptId,
		AiDetectionResponse response
	) {
		assertThatThrownBy(() -> storageService.storeSuccessfulResponse(
			TEACHER_ID,
			runId,
			attemptId,
			200,
			response,
			Instant.parse("2026-07-27T17:10:03Z")
		)).isInstanceOf(DetectionResponseStorageException.class);

		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			))
			.isEmpty();
		DetectionRun run = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.REQUESTED);
		assertThat(run.attempts()).singleElement().satisfies(attempt ->
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.REQUESTED)
		);
	}

	private AiDetectionResponse withSignals(
		AiDetectionResponse response,
		List<AiDetectionResponse.Signal> signals
	) {
		return new AiDetectionResponse(
			new AiDetectionResponse.Data(signals, response.data().stats()),
			response.error(),
			response.meta()
		);
	}

	private AiDetectionResponse.Signal copySignal(
		AiDetectionResponse.Signal signal,
		String studentRef,
		List<AiDetectionResponse.Evidence> evidence
	) {
		return new AiDetectionResponse.Signal(
			signal.signalId(),
			studentRef,
			signal.classRef(),
			signal.ruleId(),
			signal.signalType(),
			signal.displayLabel(),
			signal.score(),
			signal.rank(),
			signal.advisory(),
			signal.lifecycle(),
			signal.brief(),
			evidence
		);
	}
}
