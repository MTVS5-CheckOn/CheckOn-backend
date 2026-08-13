package com.checkon.detection.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DetectionRunControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198b000-0000-7000-8000-000000000001");
	private static final UUID STUDENT =
		UUID.fromString("0198b000-0000-7000-8000-000000000002");
	private static final UUID OTHER_TEACHER =
		UUID.fromString("0198b000-0000-7000-8000-000000000011");
	private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 8, 3);

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void fixtures() {
		jdbc.update("DELETE FROM detection_assignment_week_summaries");
		jdbc.update("DELETE FROM detection_student_status_history");
		jdbc.update("DELETE FROM kafka_inbox_events");
		jdbc.update("DELETE FROM kafka_outbox_events");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM intervention_reminders");
		jdbc.update("DELETE FROM interventions");
		jdbc.update("DELETE FROM alert_follow_up_todos");
		jdbc.update("DELETE FROM engagement_alerts");
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM student_personal_information");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		insertRoster(TEACHER, STUDENT, "운영 테스트 학생");
	}

	@Test
	@DisplayName("Given 학습 기록이 있을 때, When 탐지를 요청하면, Then 202와 Kafka 요청 Outbox를 만든다")
	void givenLearningRecords_whenRequestingDetection_thenAcceptsAndPersistsKafkaOutbox()
		throws Exception {
		insertLearningRecord(TEACHER, STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000101"),
			Instant.parse("2026-07-20T01:00:00Z"));

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Location", "/api/v1/detection-runs/{runId}".replace(
				"{runId}", jdbc.queryForObject(
					"SELECT id::text FROM detection_runs WHERE teacher_id = ?", String.class, TEACHER))))
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.attemptNumber").value(1));

		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM kafka_outbox_events WHERE status = 'PENDING'",
			Integer.class)).isEqualTo(1);
		String payload = jdbc.queryForObject("SELECT payload FROM kafka_outbox_events",
			String.class);
		assertThat(payload)
			.contains("risk-detection.requested")
			.contains("tn_")
			.contains("consent")
			.doesNotContain("운영 테스트 학생")
			.doesNotContain(STUDENT.toString());
	}

	@Test
	@DisplayName("Given 수동 vacation 실행, When 탐지를 요청하면, Then KST 월요일 기준 10주와 학기 맥락을 저장한다")
	void givenVacationContext_whenRequestingDetection_thenUsesTenKstWeekWindow()
		throws Exception {
		UUID included = UUID.fromString("0198b000-0000-7000-8000-000000000121");
		UUID excluded = UUID.fromString("0198b000-0000-7000-8000-000000000122");
		insertLearningRecord(
			TEACHER, STUDENT, included, Instant.parse("2026-05-31T15:00:00Z")
		);
		insertLearningRecord(
			TEACHER, STUDENT, excluded, Instant.parse("2026-05-31T14:59:59Z")
		);

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"analysisDate":"2026-08-03","termContext":"vacation"}
					"""))
			.andExpect(status().isAccepted());

		String snapshot = jdbc.queryForObject(
			"SELECT snapshot_payload FROM detection_runs WHERE teacher_id = ?",
			String.class,
			TEACHER
		);
		assertThat(snapshot)
			.contains("\"term_context\":\"vacation\"")
			.contains("le_" + included.toString().replace("-", ""))
			.doesNotContain("le_" + excluded.toString().replace("-", ""));
	}

	@Test
	@DisplayName("Given 과제 집계 행, When 탐지를 요청하면, Then R2 assignment window를 Kafka snapshot에 포함한다")
	void givenAssignmentSummary_whenRequestingDetection_thenIncludesR2Evidence()
		throws Exception {
		UUID summaryId = UUID.fromString("0198b000-0000-7000-8000-000000000154");
		jdbc.update("""
			INSERT INTO detection_assignment_week_summaries(
			    id, teacher_id, student_id, week_start,
			    expected_count, submitted_count, calculated_at
			) VALUES (?, ?, ?, '2026-08-03', 3, 0, now())
			""", summaryId, TEACHER, STUDENT);

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted());

		String snapshot = jdbc.queryForObject(
			"SELECT snapshot_payload FROM detection_runs WHERE teacher_id = ?",
			String.class,
			TEACHER
		);
		assertThat(snapshot)
			.contains("\"kind\":\"assignment_window\"")
			.contains("\"record_id\":\"" + summaryId + "\"")
			.contains("\"expected_count\":3")
			.contains("\"submitted_count\":0");
	}

	@Test
	@DisplayName("Given 분석 주에 returned 전환이 있을 때, When 탐지를 요청하면, Then 실제 이력 ID와 상태값을 R5 근거로 보낸다")
	void givenReturnedTransitionInAnalysisWeek_whenRequestingDetection_thenIncludesR5Evidence()
		throws Exception {
		UUID transitionId = UUID.fromString("0198b000-0000-7000-8000-000000000155");
		jdbc.update("""
			INSERT INTO detection_student_status_history(
			    id, teacher_id, student_id, occurred_at,
			    from_status, to_status, created_at
			) VALUES (?, ?, ?, '2026-08-02T15:00:00Z',
			          'enrolled', 'returned', '2026-08-02T15:00:00Z')
			""", transitionId, TEACHER, STUDENT);

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted());

		String snapshot = jdbc.queryForObject(
			"SELECT snapshot_payload FROM detection_runs WHERE teacher_id = ?",
			String.class,
			TEACHER
		);
		assertThat(snapshot)
			.contains("\"status\":\"returned\"")
			.contains("\"kind\":\"enrollment_transition\"")
			.contains("\"record_id\":\"" + transitionId + "\"")
			.contains("\"from_status\":\"enrolled\"")
			.contains("\"to_status\":\"returned\"");
	}

	@Test
	@DisplayName("Given 요청 중인 Run이 있을 때, When 같은 날짜를 다시 요청하면, Then 추가 Kafka 이벤트를 만들지 않는다")
	void givenRequestedRun_whenRequestingSameDateAgain_thenDoesNotDuplicateOutbox()
		throws Exception {
		insertLearningRecord(TEACHER, STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000102"),
			Instant.parse("2026-07-20T01:00:00Z"));

		for (int request = 0; request < 2; request++) {
			mockMvc.perform(post("/api/v1/detection-runs")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"analysisDate\":\"2026-08-03\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("REQUESTED"));
		}

		assertThat(jdbc.queryForObject("SELECT count(*) FROM kafka_outbox_events",
			Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_request_attempts",
			Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("Given 요청된 Run이 있을 때, When 소유 강사가 상태를 조회하면, Then REQUESTED 상태를 받는다")
	void givenRequestedRun_whenOwnerReadsStatus_thenReturnsRequested()
		throws Exception {
		insertLearningRecord(TEACHER, STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000103"),
			Instant.parse("2026-07-20T01:00:00Z"));
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted());
		UUID runId = jdbc.queryForObject(
			"SELECT id FROM detection_runs WHERE teacher_id = ?", UUID.class, TEACHER);

		mockMvc.perform(get("/api/v1/detection-runs/{runId}", runId)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.attemptCount").value(1))
			.andExpect(jsonPath("$.stats").isEmpty());
		mockMvc.perform(get("/api/v1/detection-runs/{runId}", runId)
				.with(teacherAuthentication(OTHER_TEACHER)))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Given 실행 이력, When 최근 실행을 조회하면, Then 운영 화면용 상태를 반환한다")
	void givenRun_whenReadingLatest_thenReturnsOperationalStatus() throws Exception {
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted());
		String runId = jdbc.queryForObject(
			"SELECT id::text FROM detection_runs WHERE teacher_id = ?", String.class, TEACHER
		);

		mockMvc.perform(get("/api/v1/detection-runs/latest")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId))
			.andExpect(jsonPath("$.status").value("REQUESTED"));
	}

	@Test
	@DisplayName("Given 근거 부족 규칙이 저장된 Run, When 상태를 조회하면, Then 규칙과 사유와 학생 수를 반환한다")
	void givenRunWithSkippedRules_whenOwnerReadsStatus_thenReturnsOperationalStats()
		throws Exception {
		insertLearningRecord(TEACHER, STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000104"),
			Instant.parse("2026-07-20T01:00:00Z"));
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted());
		UUID runId = jdbc.queryForObject(
			"SELECT id FROM detection_runs WHERE teacher_id = ?", UUID.class, TEACHER);
		jdbc.update("""
			UPDATE detection_runs
			SET status = 'SUCCEEDED', completed_at = requested_at, ai_execution_id = 'stats-test',
			    response_stats_payload = ?
			WHERE id = ?
			""", """
			{"students_evaluated":3,"signals_raised":0,"excluded_under_2w":0,"capped_out":0,
			 "rules_skipped":[
			   {"rule_id":"R2","reason":"authoritative_evidence_missing","students":1},
			   {"rule_id":"R3","reason":"authoritative_evidence_missing","students":1}
			 ]}
			""", runId);

		mockMvc.perform(get("/api/v1/detection-runs/{runId}", runId)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stats.signalsRaised").value(0))
			.andExpect(jsonPath("$.stats.rulesSkipped.length()").value(2))
			.andExpect(jsonPath("$.stats.rulesSkipped[0].ruleId").value("R2"))
			.andExpect(jsonPath("$.stats.rulesSkipped[0].reason")
				.value("authoritative_evidence_missing"))
			.andExpect(jsonPath("$.stats.rulesSkipped[0].students").value(1));
	}

	@Test
	@DisplayName("Given 활성 학생의 학습 기록이 0건일 때, When 탐지를 요청하면, Then 0건 근거를 담은 Kafka Outbox를 만든다")
	void givenActiveStudentWithoutLearningRecords_whenRequestingDetection_thenQueuesZeroActivityEvidence()
		throws Exception {
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("REQUESTED"));

		String payload = jdbc.queryForObject("SELECT payload FROM kafka_outbox_events",
			String.class);
		assertThat(payload)
			.contains("detection_evidence")
			.contains("student_week_activity")
			.contains("\"activity_count\":0");
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(roleAuthentication(AccountRole.PARENT))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isForbidden());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM kafka_outbox_events",
			Integer.class)).isEqualTo(1);
	}

	private void insertRoster(UUID teacherId, UUID studentId, String alias) {
		RosterTestFixture.insertTeacher(jdbc, teacherId);
		Instant now = Instant.parse("2026-06-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, alias, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, created_at)
			VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""", teacherId, studentId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
	}

	private void insertLearningRecord(UUID teacherId, UUID studentId, UUID recordId, Instant occurredAt) {
		jdbc.update("""
			INSERT INTO learning_records
			(id, teacher_id, student_id, record_type, occurred_at, source_type,
			 correct, duration_sec, created_at, updated_at)
			VALUES (?, ?, ?, 'SOLVE', ?, 'integration-test', true, 120, ?, ?)
			""", recordId, teacherId, studentId, occurredAt.atOffset(ZoneOffset.UTC),
			occurredAt.atOffset(ZoneOffset.UTC), occurredAt.atOffset(ZoneOffset.UTC));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		AuthenticatedAccount principal = new AuthenticatedAccount(
			UUID.randomUUID(), AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
			List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor roleAuthentication(AccountRole role) {
		AuthenticatedAccount principal = new AuthenticatedAccount(
			UUID.randomUUID(), role, null, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}
}
