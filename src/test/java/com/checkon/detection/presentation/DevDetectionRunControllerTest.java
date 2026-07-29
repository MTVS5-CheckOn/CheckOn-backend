package com.checkon.detection.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;

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
	private ObjectMapper objectMapper;

	@Test
	void preparesRunAndReturnsExistingRunForTheSameSnapshot() throws Exception {
		String requestBody = new ClassPathResource(
			"ai/detect-contract-request.json"
		).getContentAsString(StandardCharsets.UTF_8);

		byte[] responseBytes = mockMvc.perform(post("/api/dev/detection-runs")
				.header("X-Teacher-Id", TEACHER_ID)
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
				.header("X-Teacher-Id", TEACHER_ID)
				.header("X-Tenant-Id", "tn_demo_teacher")
				.queryParam("analysisDate", ANALYSIS_DATE.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId.toString()))
			.andExpect(jsonPath("$.created").value(false));

		mockMvc.perform(post("/api/dev/detection-runs")
				.header("X-Teacher-Id", TEACHER_ID)
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
}
