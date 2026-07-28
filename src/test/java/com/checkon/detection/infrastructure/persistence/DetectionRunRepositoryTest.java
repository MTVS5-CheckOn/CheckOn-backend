package com.checkon.detection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManager;

import com.checkon.detection.domain.DetectionRequestAttemptStatus;
import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;

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
	private final EntityManager entityManager;

	@Autowired
	DetectionRunRepositoryTest(
		DetectionRunRepository repository,
		EntityManager entityManager
	) {
		this.repository = repository;
		this.entityManager = entityManager;
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
}
