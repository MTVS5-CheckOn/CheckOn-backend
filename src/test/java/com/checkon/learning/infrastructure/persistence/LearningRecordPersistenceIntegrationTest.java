package com.checkon.learning.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.detection.integration.ai.AiDetectionSnapshotHasher;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.application.AiStudentAliasService;
import com.checkon.learning.application.LearningRecordSnapshotService;
import com.checkon.learning.application.SaveLearningRecordService;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.domain.LearningRecordType;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
class LearningRecordPersistenceIntegrationTest {
	@Container @ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");
	private static final UUID TEACHER = UUID.fromString("0198a000-0000-7000-8000-000000000001");
	private static final UUID STUDENT = UUID.fromString("0198a000-0000-7000-8000-000000000002");
	private static final UUID TEACHER_B = UUID.fromString("0198a000-0000-7000-8000-000000000011");
	private static final UUID STUDENT_B = UUID.fromString("0198a000-0000-7000-8000-000000000012");
	private static final Instant FROM = Instant.parse("2026-07-27T00:00:00Z");

	@Autowired JdbcTemplate jdbc;
	@Autowired SaveLearningRecordService saveService;
	@Autowired LearningRecordSnapshotService snapshotService;
	@Autowired AiStudentAliasService aliasService;
	@Autowired LearningRecordRepository records;
	@Autowired AiDetectionSnapshotHasher hasher;
	@Autowired ObjectMapper objectMapper;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired TeacherTenantDatabaseContext tenantContext;

	@BeforeEach
	void fixtures() {
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		jdbc.update("DELETE FROM teacher_profiles WHERE id = ?", TEACHER);
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'Visible Student Name', 1, ?, ?)
			""", STUDENT, FROM.atOffset(ZoneOffset.UTC), FROM.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, created_at)
			VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, FROM.atOffset(ZoneOffset.UTC), FROM.atOffset(ZoneOffset.UTC));
	}

	@Test
	void storesNullableAndDuplicateExternalReferencesWithoutUniqueMetadata() {
		LearningRecord first = saveService.save(TEACHER, draft(null, FROM.plusSeconds(2), true));
		LearningRecord second = saveService.save(TEACHER, draft("same-ref", FROM.plusSeconds(3), true));
		LearningRecord third = saveService.save(TEACHER, draft("same-ref", FROM.plusSeconds(4), false));
		assertThat(first.id().version()).isEqualTo(7);
		assertThat(first.externalRecordRef()).isNull();
		assertThat(List.of(second.externalRecordRef(), third.externalRecordRef()))
			.containsOnly("same-ref");
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM pg_constraint
			WHERE conrelid = 'learning_records'::regclass AND contype = 'u'
			  AND pg_get_constraintdef(oid) ILIKE '%external_record_ref%'
			""", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM pg_indexes WHERE tablename = 'learning_records'
			AND indexdef ILIKE 'CREATE UNIQUE%external_record_ref%'
			""", Integer.class)).isZero();
	}

	@Test
	void databaseRejectsMissingForeignKeysAndInvalidCheckValues() {
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO learning_records
			(teacher_id, student_id, record_type, occurred_at, source_type, created_at, updated_at)
			VALUES (?, ?, 'SOLVE', now(), 'test', now(), now())
			""", UUID.randomUUID(), STUDENT)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO learning_records
			(teacher_id, student_id, record_type, occurred_at, source_type,
			 duration_sec, created_at, updated_at)
			VALUES (?, ?, 'SOLVE', now(), 'test', -1, now(), now())
			""", TEACHER, STUDENT)).hasRootCauseInstanceOf(java.sql.SQLException.class);
	}

	@Test
	void buildsPseudonymizedDeterministicSnapshotAndChangesHashWithRecordData() throws Exception {
		saveService.save(TEACHER, draft(null, FROM.plusSeconds(20), false));
		saveService.save(TEACHER, draft(null, FROM.plusSeconds(10), true));
		saveService.save(TEACHER, draft(null, FROM.plusSeconds(200), true));
		insertSecondTenant();
		saveService.save(TEACHER_B, new LearningRecord.Draft(TEACHER_B, STUDENT_B,
			null, LearningRecordType.SUBMIT, FROM.plusSeconds(15), "other-tenant", null,
			null, null, null, null, null, null, null, null));
		var first = snapshotService.build(TEACHER, LocalDate.parse("2026-07-27"),
			"normal", FROM, FROM.plusSeconds(100));
		var second = snapshotService.build(TEACHER, LocalDate.parse("2026-07-27"),
			"normal", FROM, FROM.plusSeconds(100));
		assertThat(first.learningEvents()).extracting(event -> event.occurredAt().toInstant())
			.isSorted();
		assertThat(first.learningEvents()).hasSize(2)
			.noneSatisfy(event -> assertThat(event.source()).isEqualTo("other-tenant"));
		assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
		String json = objectMapper.writeValueAsString(first);
		assertThat(json).doesNotContain("Visible Student Name").doesNotContain(STUDENT.toString());
		assertThat(first.students()).singleElement().satisfies(student ->
			assertThat(student.studentRef()).matches("st_[0-9a-f]{32}"));
		assertThat(first.alertContext()).isEmpty();

		LearningRecord record = records.findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
			TEACHER, FROM, FROM.plusSeconds(100)).getFirst();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			tenantContext.setCurrentTeacher(TEACHER);
			jdbc.update("""
				UPDATE learning_records SET duration_sec = 999, updated_at = ?
				WHERE id = ? AND teacher_id = ?
				""", Instant.parse("2026-08-04T00:00:00Z").atOffset(ZoneOffset.UTC),
				record.id(), TEACHER);
		});
		var changed = snapshotService.build(TEACHER, LocalDate.parse("2026-07-27"),
			"normal", FROM, FROM.plusSeconds(100));
		assertThat(hasher.hash(changed)).isNotEqualTo(hasher.hash(first));
		var prepared = snapshotService.prepare(TEACHER, "tenant-a",
			LocalDate.parse("2026-08-03"), LocalDate.parse("2026-07-27"), "normal",
			FROM, FROM.plusSeconds(100));
		assertThat(prepared.created()).isTrue();
		assertThat(jdbc.queryForObject("SELECT snapshot_payload FROM detection_runs WHERE id = ?",
			String.class, prepared.runId())).contains("st_").doesNotContain("Visible Student Name");
	}

	@Test
	void allocatesOneStableAliasUnderConcurrentCreation() throws Exception {
		var pool = Executors.newFixedThreadPool(4);
		try {
			Callable<String> task = () -> new TransactionTemplate(transactionManager).execute(status -> {
				tenantContext.setCurrentTeacher(TEACHER);
				return aliasService.getOrCreate(TEACHER, STUDENT);
			});
			var futures = pool.invokeAll(List.of(task, task, task, task));
			List<String> values = futures.stream().map(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new AssertionError(exception); }
			}).toList();
			assertThat(values).containsOnly(values.getFirst());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_student_aliases", Integer.class))
				.isEqualTo(1);
		} finally { pool.shutdownNow(); }
	}

	private LearningRecord.Draft draft(String external, Instant occurredAt, Boolean correct) {
		return new LearningRecord.Draft(TEACHER, STUDENT, null, LearningRecordType.SOLVE,
			occurredAt, "trackB", external, correct, 180, 800, "reading", "common",
			"infer", "mcq", null);
	}

	private void insertSecondTenant() {
		RosterTestFixture.insertTeacher(jdbc, TEACHER_B);
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'Other Student', 2, ?, ?)
			""", STUDENT_B, FROM.atOffset(ZoneOffset.UTC), FROM.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, created_at)
			VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""", TEACHER_B, STUDENT_B, FROM.atOffset(ZoneOffset.UTC), FROM.atOffset(ZoneOffset.UTC));
	}
}
