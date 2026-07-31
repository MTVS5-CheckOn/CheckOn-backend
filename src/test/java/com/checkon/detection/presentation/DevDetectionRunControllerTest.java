package com.checkon.detection.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.infrastructure.persistence.DetectionSignalResultRepository;
import com.checkon.detection.integration.ai.RiskDetectionClient;
import com.checkon.detection.integration.ai.RiskDetectionClientException;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class DevDetectionRunControllerTest {

	private static final UUID TEACHER_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000402");
	private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 7, 29);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DetectionRunRepository runRepository;

	@Autowired
	private DetectionSignalResultRepository signalResultRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private RiskDetectionClient riskDetectionClient;

	@BeforeEach
	void createTeacherFixture() {
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Test
	void preparesRunAndReturnsExistingRunForTheSameSnapshot() throws Exception {
		String requestBody = new ClassPathResource(
			"ai/detect-contract-request.json"
		).getContentAsString(StandardCharsets.UTF_8);

		byte[] responseBytes = mockMvc.perform(post("/api/dev/detection-runs")
				.with(teacherAuthentication(TEACHER_ID))
				.header("X-Tenant-Id", "tn_demo_teacher")
				.queryParam("analysisDate", ANALYSIS_DATE.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.status").value("PREPARED"))
			.andExpect(jsonPath("$.analysisDate").value("2026-07-29"))
			.andExpect(jsonPath("$.created").value(true))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		UUID runId = UUID.fromString(
			objectMapper.readTree(responseBytes).get("runId").asText()
		);
		var run = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.PREPARED);
		assertThat(run.id().version()).isEqualTo(7);
		assertThat(run.idempotencyKey())
			.isEqualTo("tn_demo_teacher:2026-07-29");
		assertThat(objectMapper.readTree(run.snapshotPayload())
			.at("/snapshot_meta/snapshot_hash").asText())
			.isEqualTo(run.snapshotHash());

		mockMvc.perform(post("/api/dev/detection-runs")
				.with(teacherAuthentication(TEACHER_ID))
				.header("X-Tenant-Id", "tn_demo_teacher")
				.queryParam("analysisDate", ANALYSIS_DATE.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId.toString()))
			.andExpect(jsonPath("$.created").value(false));

		mockMvc.perform(post("/api/dev/detection-runs")
				.with(teacherAuthentication(TEACHER_ID))
				.header("X-Tenant-Id", "tn_demo_teacher")
				.queryParam("analysisDate", ANALYSIS_DATE.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody.replace(
					"\"term_context\": \"normal\"",
					"\"term_context\": \"exam\""
				)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DETECTION_RUN_CONFLICT"));
	}

	@Test
	void executesPreparedRunAndReturnsSucceeded() throws Exception {
		LocalDate analysisDate = LocalDate.of(2026, 7, 30);
		UUID runId = prepareRunThroughApi(TEACHER_ID, analysisDate);
		when(riskDetectionClient.detect(any(), any()))
			.thenReturn(readAiResponse());

		mockMvc.perform(post(
				"/api/dev/detection-runs/{runId}/execute",
				runId
			)
				.with(teacherAuthentication(TEACHER_ID))
				.header("X-Tenant-Id", "tn_demo_teacher"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId.toString()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow().status())
			.isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			))
			.hasSize(2);

		mockMvc.perform(post(
				"/api/dev/detection-runs/{runId}/execute",
				runId
			)
				.with(teacherAuthentication(TEACHER_ID))
				.header("X-Tenant-Id", "tn_demo_teacher"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code")
				.value("DETECTION_RUN_STATE_CONFLICT"));
	}

	@Test
	void returnsBadGatewayAndPreservesAiFailure() throws Exception {
		LocalDate analysisDate = LocalDate.of(2026, 7, 31);
		UUID runId = prepareRunThroughApi(TEACHER_ID, analysisDate);
		when(riskDetectionClient.detect(any(), any()))
			.thenThrow(RiskDetectionClientException.httpError(503, null));

		mockMvc.perform(post(
				"/api/dev/detection-runs/{runId}/execute",
				runId
			)
				.with(teacherAuthentication(TEACHER_ID))
				.header("X-Tenant-Id", "tn_demo_teacher"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("HTTP_ERROR"));

		var run = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.FAILED);
		assertThat(run.errorCode()).isEqualTo("HTTP_ERROR");
		assertThat(run.attempts()).singleElement().satisfies(attempt ->
			assertThat(attempt.httpStatus()).isEqualTo(503)
		);
	}

	@Test
	void hidesRunFromAnotherTeacherBeforeCallingAi() throws Exception {
		LocalDate analysisDate = LocalDate.of(2026, 8, 1);
		UUID runId = prepareRunThroughApi(TEACHER_ID, analysisDate);
		UUID anotherTeacherId =
			UUID.fromString("019846dc-7c00-7000-8000-000000000499");
		RosterTestFixture.insertTeacher(jdbcTemplate, anotherTeacherId);

		mockMvc.perform(post(
				"/api/dev/detection-runs/{runId}/execute",
				runId
			)
				.with(teacherAuthentication(anotherTeacherId))
				.header("X-Tenant-Id", "tn_demo_teacher"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));

		verifyNoInteractions(riskDetectionClient);
	}

	@Test
	void rejectsUnauthenticatedAndNonTeacherCallersBeforeTenantUseCase() throws Exception {
		mockMvc.perform(post("/api/dev/detection-runs"))
			.andExpect(status().isUnauthorized());

		AuthenticatedAccount parent = new AuthenticatedAccount(
			UUID.randomUUID(),
			AccountRole.PARENT,
			null,
			UUID.randomUUID()
		);
		var parentAuthentication = UsernamePasswordAuthenticationToken.authenticated(
			parent,
			null,
			java.util.List.of(new SimpleGrantedAuthority("ROLE_PARENT"))
		);
		mockMvc.perform(post("/api/dev/detection-runs")
				.with(authentication(parentAuthentication)))
			.andExpect(status().isForbidden());
	}

	private UUID prepareRunThroughApi(
		UUID teacherId,
		LocalDate analysisDate
	) throws Exception {
		String requestBody = new ClassPathResource(
			"ai/detect-contract-request.json"
		).getContentAsString(StandardCharsets.UTF_8);
		byte[] responseBytes = mockMvc.perform(post("/api/dev/detection-runs")
				.with(teacherAuthentication(teacherId))
				.header("X-Tenant-Id", "tn_demo_teacher")
				.queryParam("analysisDate", analysisDate.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		return UUID.fromString(
			objectMapper.readTree(responseBytes).get("runId").asText()
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
			java.util.List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}

	private AiDetectionResponse readAiResponse() throws Exception {
		try (var input = new ClassPathResource(
			"ai/detect-contract-response.json"
		).getInputStream()) {
			return objectMapper.readValue(input, AiDetectionResponse.class);
		}
	}
}
