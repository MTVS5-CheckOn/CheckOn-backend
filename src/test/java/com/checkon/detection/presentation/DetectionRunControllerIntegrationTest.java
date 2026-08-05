package com.checkon.detection.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.detection.application.DetectionTenantKey;
import com.checkon.detection.domain.DetectionRequestAttemptStatus;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.infrastructure.persistence.DetectionSignalResultRepository;
import com.checkon.detection.integration.ai.AiDetectionRequestHeaders;
import com.checkon.detection.integration.ai.RiskDetectionClient;
import com.checkon.detection.integration.ai.RiskDetectionClientException;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.ObjectMapper;

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
	private static final UUID OTHER_STUDENT =
		UUID.fromString("0198b000-0000-7000-8000-000000000012");
	private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 8, 3);

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper objectMapper;
	@Autowired DetectionRunRepository runRepository;
	@Autowired DetectionSignalResultRepository signalRepository;

	@MockitoBean RiskDetectionClient riskDetectionClient;

	@BeforeEach
	void fixtures() {
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
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		insertRoster(TEACHER, STUDENT, "운영 테스트 학생");
		insertRoster(OTHER_TEACHER, OTHER_STUDENT, "다른 강사 학생");
	}

	@Test
	void authenticatedTeacherRunsServerOwnedPseudonymizedSnapshot()
		throws Exception {
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000101"),
			Instant.parse("2026-07-20T01:00:00Z")
		);
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000102"),
			Instant.parse("2026-05-01T01:00:00Z")
		);
		insertLearningRecord(
			OTHER_TEACHER,
			OTHER_STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000103"),
			Instant.parse("2026-07-20T01:00:00Z")
		);
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000104"),
			Instant.parse("2026-06-08T15:00:00Z")
		);
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000105"),
			Instant.parse("2026-06-08T14:59:59Z")
		);
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000106"),
			Instant.parse("2026-08-03T15:00:00Z")
		);
		when(riskDetectionClient.detect(any(), any()))
			.thenAnswer(invocation -> validResponse(invocation.getArgument(0)));

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.header("X-Teacher-Id", OTHER_TEACHER.toString())
				.header("X-Tenant-Id", "attacker-controlled")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "analysisDate": "2026-08-03",
					  "teacherId": "0198b000-0000-7000-8000-000000000011",
					  "tenantAlias": "attacker-controlled",
					  "snapshotHash": "attacker-controlled"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.analysisDate").value("2026-08-03"))
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.attemptNumber").value(1));

		ArgumentCaptor<AiDetectionRequest> requestCaptor =
			ArgumentCaptor.forClass(AiDetectionRequest.class);
		ArgumentCaptor<AiDetectionRequestHeaders> headersCaptor =
			ArgumentCaptor.forClass(AiDetectionRequestHeaders.class);
		verify(riskDetectionClient).detect(
			requestCaptor.capture(),
			headersCaptor.capture()
		);
		AiDetectionRequest request = requestCaptor.getValue();
		assertThat(request.snapshotMeta().weekStart())
			.isEqualTo(LocalDate.of(2026, 8, 3));
		assertThat(request.learningEvents())
			.extracting(AiDetectionRequest.LearningEventSnapshot::recordId)
			.containsExactly(
				"le_0198b000000070008000000000000104",
				"le_0198b000000070008000000000000101"
			);
		assertThat(request.learningEvents()).allSatisfy(event ->
			assertThat(event.studentRef()).matches("st_[0-9a-f]{32}")
		);
		assertThat(request.students()).singleElement().satisfies(student ->
			assertThat(student.studentRef()).matches("st_[0-9a-f]{32}")
		);
		String json = objectMapper.writeValueAsString(request);
		assertThat(json)
			.doesNotContain("운영 테스트 학생")
			.doesNotContain(STUDENT.toString())
			.doesNotContain(OTHER_STUDENT.toString());
		String tenantKey = DetectionTenantKey.fromTeacherProfileId(TEACHER).value();
		assertThat(headersCaptor.getValue().tenantId()).isEqualTo(tenantKey);
		assertThat(headersCaptor.getValue().idempotencyKey().value())
			.isEqualTo(tenantKey + ":2026-08-03");
	}

	@Test
	void rejectsMissingDataAndNonTeacherCallersAndDoesNotExposeDevEndpoint()
		throws Exception {
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("NO_LEARNING_RECORDS"));

		mockMvc.perform(post("/api/v1/detection-runs")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(roleAuthentication(AccountRole.PARENT))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(roleAuthentication(AccountRole.STUDENT))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/dev/detection-runs")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void retriesFailedRunWithASecondAttemptAndDoesNotReexecuteSuccessfulRun()
		throws Exception {
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000201"),
			Instant.parse("2026-07-20T01:00:00Z")
		);
		when(riskDetectionClient.detect(any(), any()))
			.thenThrow(RiskDetectionClientException.networkError(null))
			.thenAnswer(invocation -> validResponse(invocation.getArgument(0)));

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("NETWORK_ERROR"));
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(false))
			.andExpect(jsonPath("$.attemptNumber").value(2));

		var run = runRepository.findByTeacherIdAndAnalysisDate(TEACHER, ANALYSIS_DATE)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(run.attempts()).hasSize(2);
		assertThat(run.attempts().getFirst().status())
			.isEqualTo(DetectionRequestAttemptStatus.FAILED);
		assertThat(run.attempts().get(1).status())
			.isEqualTo(DetectionRequestAttemptStatus.SUCCEEDED);
		assertThat(signalRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				run.id(),
				TEACHER
			))
			.hasSize(1);

		// 성공 Run은 멱등한 성공 응답을 반환하고 AI를 다시 호출하지 않는다.
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.created").value(false))
			.andExpect(jsonPath("$.attemptNumber").value(0));
		verify(riskDetectionClient, times(2)).detect(any(), any());
	}

	@Test
	void rejectsChangedSnapshotForTheSameTeacherAndSeparatesAnotherTeacherRun()
		throws Exception {
		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000301"),
			Instant.parse("2026-07-20T01:00:00Z")
		);
		insertLearningRecord(
			OTHER_TEACHER,
			OTHER_STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000302"),
			Instant.parse("2026-07-20T01:00:00Z")
		);
		when(riskDetectionClient.detect(any(), any()))
			.thenThrow(RiskDetectionClientException.networkError(null))
			.thenAnswer(invocation -> validResponse(invocation.getArgument(0)));

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isBadGateway());

		insertLearningRecord(
			TEACHER,
			STUDENT,
			UUID.fromString("0198b000-0000-7000-8000-000000000303"),
			Instant.parse("2026-07-21T01:00:00Z")
		);
		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		mockMvc.perform(post("/api/v1/detection-runs")
				.with(teacherAuthentication(OTHER_TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"analysisDate\":\"2026-08-03\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));

		var teacherRun = runRepository
			.findByTeacherIdAndAnalysisDate(TEACHER, ANALYSIS_DATE)
			.orElseThrow();
		var otherRun = runRepository
			.findByTeacherIdAndAnalysisDate(OTHER_TEACHER, ANALYSIS_DATE)
			.orElseThrow();
		assertThat(teacherRun.id()).isNotEqualTo(otherRun.id());
		assertThat(teacherRun.idempotencyKey())
			.isNotEqualTo(otherRun.idempotencyKey());
		assertThat(teacherRun.attempts()).hasSize(1);
		verify(riskDetectionClient, times(2)).detect(any(), any());
	}

	private void insertRoster(UUID teacherId, UUID studentId, String alias) {
		RosterTestFixture.insertTeacher(jdbc, teacherId);
		Instant now = Instant.parse("2026-06-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, alias, now.atOffset(ZoneOffset.UTC),
			now.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, created_at)
			VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""", teacherId, studentId, now.atOffset(ZoneOffset.UTC),
			now.atOffset(ZoneOffset.UTC));
	}

	private void insertLearningRecord(
		UUID teacherId,
		UUID studentId,
		UUID recordId,
		Instant occurredAt
	) {
		jdbc.update("""
			INSERT INTO learning_records
			(id, teacher_id, student_id, record_type, occurred_at, source_type,
			 correct, duration_sec, created_at, updated_at)
			VALUES (?, ?, ?, 'SOLVE', ?, 'integration-test', true, 120, ?, ?)
			""", recordId, teacherId, studentId, occurredAt.atOffset(ZoneOffset.UTC),
			occurredAt.atOffset(ZoneOffset.UTC), occurredAt.atOffset(ZoneOffset.UTC));
	}

	private AiDetectionResponse validResponse(AiDetectionRequest request) {
		AiDetectionRequest.StudentSnapshot student = request.students().getFirst();
		AiDetectionRequest.LearningEventSnapshot event =
			request.learningEvents().getFirst();
		return new AiDetectionResponse(
			new AiDetectionResponse.Data(
				List.of(new AiDetectionResponse.Signal(
					"signal-1",
					student.studentRef(),
					student.classRef(),
					"R1",
					"acc_drop",
					"정답률 하락",
					0.8,
					1,
					"new",
					new AiDetectionResponse.Brief("검토가 필요합니다.", true, false),
					List.of(new AiDetectionResponse.Evidence(
						"learning_event",
						event.recordId(),
						"학습 기록 근거"
					))
				)),
				new AiDetectionResponse.Stats(1, 1, 0, 0, List.of())
			),
			null,
			new AiDetectionResponse.Meta("execution-1", Map.of("pipeline", "test"))
		);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor
	teacherAuthentication(UUID teacherId) {
		AuthenticatedAccount principal = new AuthenticatedAccount(
			UUID.randomUUID(),
			AccountRole.TEACHER,
			teacherId,
			UUID.randomUUID()
		);
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor
	roleAuthentication(AccountRole role) {
		AuthenticatedAccount principal = new AuthenticatedAccount(
			UUID.randomUUID(),
			role,
			null,
			UUID.randomUUID()
		);
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
		));
	}
}
