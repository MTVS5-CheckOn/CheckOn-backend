package com.checkon.detection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.checkon.detection.domain.DetectionLifecycle;
import com.checkon.detection.domain.DetectionResultEvidenceDraft;
import com.checkon.detection.domain.DetectionRequestAttemptStatus;
import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.domain.DetectionSignalResult;
import com.checkon.support.RosterTestFixture;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DetectionRunRepositoryTest {

	private static final UUID RUN_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000001");
	private static final UUID TEACHER_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000002");
	private static final UUID FIRST_ATTEMPT_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000010");
	private static final UUID SECOND_ATTEMPT_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000011");
	private static final String SNAPSHOT_HASH =
		"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	private final DetectionRunRepository repository;
	private final DetectionSignalResultRepository signalResultRepository;
	private final EntityManager entityManager;
	private final JdbcTemplate jdbcTemplate;

	@Autowired
	DetectionRunRepositoryTest(
		DetectionRunRepository repository,
		DetectionSignalResultRepository signalResultRepository,
		EntityManager entityManager,
		JdbcTemplate jdbcTemplate
	) {
		this.repository = repository;
		this.signalResultRepository = signalResultRepository;
		this.entityManager = entityManager;
		this.jdbcTemplate = jdbcTemplate;
	}

	@BeforeEach
	void createTeacherFixture() {
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Test
	void preservesEveryRequestAttemptWhenReloadingRun() {
		DetectionRun run = prepareRun();
		run.startAttempt(
			FIRST_ATTEMPT_ID,
			"request-1",
			Instant.parse("2026-07-27T17:10:00Z")
		);
		run.markFailed(
			FIRST_ATTEMPT_ID,
			null,
			"TIMEOUT",
			Instant.parse("2026-07-27T17:10:30Z")
		);
		run.startAttempt(
			SECOND_ATTEMPT_ID,
			"request-2",
			Instant.parse("2026-07-27T17:11:00Z")
		);

		repository.saveAndFlush(run);
		entityManager.clear();

		DetectionRun reloaded = repository
			.findByTeacherIdAndAnalysisDate(
				TEACHER_ID,
				LocalDate.of(2026, 7, 28)
			)
			.orElseThrow();

		assertThat(reloaded.status()).isEqualTo(DetectionRunStatus.REQUESTED);
		assertThat(reloaded.snapshotHash()).isEqualTo(SNAPSHOT_HASH);
		assertThat(reloaded.snapshotPayload())
			.isEqualTo("{\"snapshot_meta\":{\"week_start\":\"2026-07-20\"}}");
		assertThat(reloaded.attempts()).hasSize(2);
		assertThat(reloaded.attempts().getFirst().status())
			.isEqualTo(DetectionRequestAttemptStatus.FAILED);
		assertThat(reloaded.attempts().getFirst().errorCode()).isEqualTo("TIMEOUT");
		assertThat(reloaded.attempts().getLast().status())
			.isEqualTo(DetectionRequestAttemptStatus.REQUESTED);
	}

	@Test
	void doesNotFindRunThroughAnotherTeacherBoundary() {
		repository.saveAndFlush(prepareRun());

		assertThat(repository.findByIdAndTeacherId(
			RUN_ID,
			UUID.fromString("019846dc-7c00-7000-8000-000000000099")
		)).isEmpty();
	}

	@Test
	void storesSevenSignalsAndTwentyOneEvidenceRows() {
		repository.saveAndFlush(prepareRun());
		signalResultRepository.saveAllAndFlush(createDemoResults());
		entityManager.clear();

		List<DetectionSignalResult> reloaded =
			signalResultRepository
				.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				RUN_ID,
				TEACHER_ID
			);

		assertThat(reloaded).hasSize(7);
		assertThat(reloaded)
			.flatExtracting(DetectionSignalResult::evidence)
			.hasSize(21);
		assertThat(reloaded)
			.filteredOn(result -> result.studentRef().equals("st_10"))
			.singleElement()
			.extracting(DetectionSignalResult::lifecycle)
			.isEqualTo(DetectionLifecycle.ONGOING);
		assertThat(reloaded)
			.filteredOn(result -> result.signalType().equals("return_care"))
			.singleElement()
			.extracting(DetectionSignalResult::rank)
			.isEqualTo(4);
	}

	@Test
	void rejectsDuplicateExternalSignalId() {
		repository.saveAndFlush(prepareRun());
		List<DetectionSignalResult> results = createDemoResults();
		signalResultRepository.saveAndFlush(results.getFirst());

		DetectionSignalResult duplicate = createSignal(
			99,
			results.getFirst().externalSignalId(),
			"st_99",
			"cl_a1",
			"R1",
			"acc_drop",
			1,
			DetectionLifecycle.NEW,
			BigDecimal.ONE
		);

		assertThatThrownBy(() -> signalResultRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateAiRequestIdWhilePreservingAttemptNumbers() {
		DetectionRun run = prepareRun();
		run.startAttempt(
			FIRST_ATTEMPT_ID,
			"duplicate-request-id",
			Instant.parse("2026-07-27T17:10:00Z")
		);
		run.markFailed(
			FIRST_ATTEMPT_ID,
			null,
			"NETWORK_ERROR",
			Instant.parse("2026-07-27T17:10:30Z")
		);
		run.startAttempt(
			SECOND_ATTEMPT_ID,
			"duplicate-request-id",
			Instant.parse("2026-07-27T17:11:00Z")
		);

		assertThat(run.attempts())
			.extracting(attempt -> attempt.attemptNumber())
			.containsExactly(1, 2);
		assertThatThrownBy(() -> repository.saveAndFlush(run))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void detectionRunRequiresExistingTeacherProfile() {
		DetectionRun run = DetectionRun.prepare(
			UUID.fromString("019846dc-7c00-7000-8000-000000000090"),
			UUID.fromString("019846dc-7c00-7000-8000-000000000099"),
			LocalDate.of(2026, 7, 30),
			LocalDate.of(2026, 7, 20),
			"missing-teacher:2026-07-30",
			SNAPSHOT_HASH,
			"{\"snapshot_meta\":{}}",
			Instant.parse("2026-07-29T17:00:00Z")
		);

		assertThatThrownBy(() -> repository.saveAndFlush(run))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private DetectionRun prepareRun() {
		return DetectionRun.prepare(
			RUN_ID,
			TEACHER_ID,
			LocalDate.of(2026, 7, 28),
			LocalDate.of(2026, 7, 20),
			"tn_demo_teacher:2026-07-28",
			SNAPSHOT_HASH,
			"{\"snapshot_meta\":{\"week_start\":\"2026-07-20\"}}",
			Instant.parse("2026-07-27T17:09:59Z")
		);
	}

	private List<DetectionSignalResult> createDemoResults() {
		List<DetectionSignalResult> results = new ArrayList<>();
		results.add(createSignal(
			1, "0d634124-11ba-5151-9414-bc4c4f4ff58e",
			"st_02", "cl_a1", "R1", "acc_drop", 1,
			DetectionLifecycle.NEW, BigDecimal.ONE
		));
		results.add(createSignal(
			2, "542c088b-fd38-53dd-af87-7fcad992cd5c",
			"st_03", "cl_a1", "R4", "hidden_risk", 2,
			DetectionLifecycle.NEW, new BigDecimal("0.6666666666666661")
		));
		results.add(createSignal(
			3, "a72d5e01-08cc-51bc-861e-e4a1c4999942",
			"st_04", "cl_a1", "R2", "submit_drop", 3,
			DetectionLifecycle.NEW, BigDecimal.ZERO
		));
		results.add(createSignal(
			4, "deccfc8f-1cce-5dbd-a577-3b0acdc02d19",
			"st_10", "cl_b2", "R1", "acc_drop", 1,
			DetectionLifecycle.ONGOING, BigDecimal.ONE
		));
		results.add(createSignal(
			5, "bc2f4dcc-5117-51ea-ae33-65ae16db9224",
			"st_07", "cl_b2", "R6", "type_bias", 2,
			DetectionLifecycle.NEW, BigDecimal.ONE
		));
		results.add(createSignal(
			6, "1a2677f5-3256-5ba1-b74a-7ae77ab8a298",
			"st_09", "cl_b2", "R3", "volume_gap", 3,
			DetectionLifecycle.NEW, new BigDecimal("0.0909090909090909")
		));
		results.add(createSignal(
			7, "d040d2da-7d61-52e0-8c38-12edf09b0ef6",
			"st_08", "cl_b2", "R5", "return_care", 4,
			DetectionLifecycle.NEW, BigDecimal.ONE
		));
		return results;
	}

	private DetectionSignalResult createSignal(
		int sequence,
		String externalSignalId,
		String studentRef,
		String classRef,
		String ruleId,
		String signalType,
		int rank,
		DetectionLifecycle lifecycle,
		BigDecimal score
	) {
		List<DetectionResultEvidenceDraft> evidence = new ArrayList<>();
		for (int index = 1; index <= 3; index++) {
			evidence.add(new DetectionResultEvidenceDraft(
				uuidFor(sequence * 10 + index),
				"learning_event",
				"le_" + (sequence * 100 + index),
				signalType + " 근거 기록"
			));
		}

		return DetectionSignalResult.create(
			uuidFor(sequence),
			RUN_ID,
			externalSignalId,
			studentRef,
			classRef,
			ruleId,
			signalType,
			ruleId + " 표시명",
			score,
			rank,
			lifecycle,
			signalType + " 브리핑",
			true,
			false,
			evidence,
			Instant.parse("2026-07-27T17:10:03Z")
		);
	}

	private UUID uuidFor(int sequence) {
		return UUID.fromString(
			"019846dc-7c00-7000-8000-" + String.format("%012x", sequence)
		);
	}
}
