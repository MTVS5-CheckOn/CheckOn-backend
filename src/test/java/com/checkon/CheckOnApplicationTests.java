package com.checkon;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkon.support.RosterTestFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class CheckOnApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");
	private final JdbcTemplate jdbcTemplate;
	private final KafkaTemplate<?, ?> kafkaTemplate;
	private final ConsumerFactory<?, ?> consumerFactory;
	private final MockMvc mockMvc;

	@Autowired
	CheckOnApplicationTests(
		JdbcTemplate jdbcTemplate,
		KafkaTemplate<?, ?> kafkaTemplate,
		ConsumerFactory<?, ?> consumerFactory,
		MockMvc mockMvc
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.kafkaTemplate = kafkaTemplate;
		this.consumerFactory = consumerFactory;
		this.mockMvc = mockMvc;
	}

	@Test
	void contextLoads() {
		assertThat(kafkaTemplate).isNotNull();
	}

	@Test
	void configuresDetectionSnapshotKafkaCapacityAndCompression() {
		assertThat(kafkaTemplate.getProducerFactory().getConfigurationProperties())
			.containsEntry("max.request.size", "6291456")
			.containsEntry("compression.type", "zstd");
		assertThat(consumerFactory.getConfigurationProperties())
			.containsEntry("max.partition.fetch.bytes", "6291456");
	}

	@Test
	void exposesSwaggerUiAndCodeConfirmedOpenApiDocumentWithoutAuthentication()
		throws Exception {
		mockMvc.perform(get("/openapi/dashboard-api.yaml"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("title: CheckOn API")));

		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
	}

	@Test
	void postgresqlSupportsUuidV7() {
		Boolean isVersion7 = jdbcTemplate.queryForObject(
			"SELECT uuid_extract_version(uuidv7()) = 7",
			Boolean.class
		);

		assertTrue(Boolean.TRUE.equals(isVersion7));
	}

	@Test
	void postgresqlInstantToTimestamptz() {
		Instant expected = Instant.parse("2026-07-27T12:34:56Z");

		Instant actual = jdbcTemplate.queryForObject(
			"SELECT CAST(? AS timestamptz)",
			(resultSet, rowNumber) ->
				resultSet.getObject(1, OffsetDateTime.class).toInstant(),
			expected.atOffset(ZoneOffset.UTC)
		);

		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("Given Flyway가 실행되면 When 현재 스키마를 조회할 때 Then 학생·학부모 테넌트 관계까지 생성한다")
	void flywayCreatesCurrentApplicationTables() {
		String detectionRuns = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.detection_runs')::text",
			String.class
		);
		String detectionAttempts = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.detection_request_attempts')::text",
			String.class
		);
		String rosterRelationships = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.teacher_student_relationships')::text",
			String.class
		);
		String alertFollowUpTodos = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.alert_follow_up_todos')::text",
			String.class
		);
		String studentPersonalInformation = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.student_personal_information')::text",
			String.class
		);
		String kafkaOutbox = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.kafka_outbox_events')::text",
			String.class
		);
		String kafkaInbox = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.kafka_inbox_events')::text",
			String.class
		);
		String assignmentEvidenceProjection = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.detection_assignment_week_summaries')::text",
			String.class
		);
		String statusEvidenceHistory = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.detection_student_status_history')::text",
			String.class
		);
		String problemGenerationRequests = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.problem_generation_requests')::text",
			String.class
		);
		String problemGenerationExecutions = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.problem_generation_executions')::text",
			String.class
		);
		String problemGenerationOutbox = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.problem_generation_outbox')::text",
			String.class
		);
		String problemGenerationConsumedEvents = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.problem_generation_consumed_events')::text",
			String.class
		);
		String aiTenantAliases = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.ai_tenant_aliases')::text",
			String.class
		);
		String aiClassAliases = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.ai_class_aliases')::text",
			String.class
		);
		String problemGenerationItems = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.problem_generation_items')::text",
			String.class
		);
		String savedProblemSets = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.saved_problem_sets')::text",
			String.class
		);
		String problemAssignments = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.problem_assignments')::text",
			String.class
		);
		String parentProfiles = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.parent_profiles')::text",
			String.class
		);
		String parentTeacherRelationships = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.parent_teacher_relationships')::text",
			String.class
		);
		String parentStudentRelationships = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.parent_student_relationships')::text",
			String.class
		);
		Integer classManagementColumns = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'class_groups'
			  AND column_name IN ('subject', 'memo')
			""", Integer.class);
		Integer advisoryColumns = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'detection_signal_results'
			  AND column_name = 'advisory'
			""", Integer.class);
		Integer structuredSignalColumns = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'detection_signal_results'
			  AND column_name IN ('metric', 'observed', 'baseline', 'sample_size')
			""", Integer.class);
		Integer structuredEvidenceColumns = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'detection_result_evidence'
			  AND column_name IN ('role', 'observed', 'sample_size', 'occurred_on')
			""", Integer.class);
		String evidenceRoleNullable = jdbcTemplate.queryForObject("""
			SELECT is_nullable
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'detection_result_evidence'
			  AND column_name = 'role'
			""", String.class);

		assertThat(detectionRuns).isEqualTo("detection_runs");
		assertThat(detectionAttempts).isEqualTo("detection_request_attempts");
		assertThat(rosterRelationships).isEqualTo("teacher_student_relationships");
		assertThat(alertFollowUpTodos).isEqualTo("alert_follow_up_todos");
		assertThat(studentPersonalInformation).isEqualTo("student_personal_information");
		assertThat(kafkaOutbox).isEqualTo("kafka_outbox_events");
		assertThat(kafkaInbox).isEqualTo("kafka_inbox_events");
		assertThat(assignmentEvidenceProjection)
			.isEqualTo("detection_assignment_week_summaries");
		assertThat(statusEvidenceHistory).isEqualTo("detection_student_status_history");
		assertThat(problemGenerationRequests).isEqualTo("problem_generation_requests");
		assertThat(problemGenerationExecutions).isEqualTo("problem_generation_executions");
		assertThat(problemGenerationOutbox).isEqualTo("problem_generation_outbox");
		assertThat(problemGenerationConsumedEvents).isEqualTo("problem_generation_consumed_events");
		assertThat(aiTenantAliases).isEqualTo("ai_tenant_aliases");
		assertThat(aiClassAliases).isEqualTo("ai_class_aliases");
		assertThat(problemGenerationItems).isEqualTo("problem_generation_items");
		assertThat(savedProblemSets).isEqualTo("saved_problem_sets");
		assertThat(problemAssignments).isEqualTo("problem_assignments");
		assertThat(parentProfiles).isEqualTo("parent_profiles");
		assertThat(parentTeacherRelationships).isEqualTo("parent_teacher_relationships");
		assertThat(parentStudentRelationships).isEqualTo("parent_student_relationships");
		assertThat(classManagementColumns).isEqualTo(2);
		assertThat(advisoryColumns).isEqualTo(1);
		assertThat(structuredSignalColumns).isEqualTo(4);
		assertThat(structuredEvidenceColumns).isEqualTo(4);
		assertThat(evidenceRoleNullable).isEqualTo("NO");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
			String.class
		)).isEqualTo("34");
	}

	@Test
	void rejectsSecondDetectionRunForSameTeacherAndAnalysisDate() {
		String insertSql = """
			INSERT INTO detection_runs (
			    id,
			    teacher_id,
			    analysis_date,
			    week_start,
			    idempotency_key,
			    snapshot_hash,
			    snapshot_payload
			)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""";
		var teacherId = UUID.randomUUID();
		RosterTestFixture.insertTeacher(jdbcTemplate, teacherId);
		var analysisDate = LocalDate.of(2026, 7, 28);
		var weekStart = LocalDate.of(2026, 7, 20);
		var snapshotHash =
			"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

		jdbcTemplate.update(
			insertSql,
			UUID.randomUUID(),
			teacherId,
			analysisDate,
			weekStart,
			"tn_demo_teacher:2026-07-28",
			snapshotHash,
			"{\"snapshot_meta\":{}}"
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			insertSql,
			UUID.randomUUID(),
			teacherId,
			analysisDate,
			weekStart,
			"another-key",
			snapshotHash,
			"{\"snapshot_meta\":{}}"
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsFailedAttemptWithoutErrorCode() {
		var runId = UUID.randomUUID();
		var teacherId = UUID.randomUUID();
		RosterTestFixture.insertTeacher(jdbcTemplate, teacherId);
		jdbcTemplate.update(
			"""
				INSERT INTO detection_runs (
				    id,
				    teacher_id,
				    analysis_date,
				    week_start,
				    idempotency_key,
				    snapshot_hash,
				    snapshot_payload
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
			runId,
			teacherId,
			LocalDate.of(2026, 7, 28),
			LocalDate.of(2026, 7, 20),
			"tn_demo_teacher:2026-07-28",
			"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			"{\"snapshot_meta\":{}}"
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"""
				INSERT INTO detection_request_attempts (
				    detection_run_id,
				    request_id,
				    attempt_number,
				    status,
				    requested_at,
				    completed_at,
				    http_status
				)
				VALUES (?, ?, ?, 'FAILED', ?, ?, 504)
				""",
			runId,
			"request-1",
			1,
			java.time.OffsetDateTime.parse("2026-07-28T02:10:00+09:00"),
			java.time.OffsetDateTime.parse("2026-07-28T02:10:30+09:00")
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
