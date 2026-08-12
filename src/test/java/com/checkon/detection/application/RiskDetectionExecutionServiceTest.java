package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.detection.domain.DetectionRequestAttemptStatus;
import com.checkon.detection.domain.DetectionRun;
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
@Testcontainers
class RiskDetectionExecutionServiceTest {

	private static final UUID TEACHER_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000302");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@MockitoBean
	private RiskDetectionClient riskDetectionClient;

	@Autowired
	private RiskDetectionExecutionService executionService;

	@Autowired
	private DetectionRunRepository runRepository;

	@Autowired
	private DetectionSignalResultRepository signalResultRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void createTeacherFixture() {
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Test
	void sendsStoredSnapshotAndPersistsTheSuccessfulResponse() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000311");
		prepareRun(runId, LocalDate.of(2026, 7, 28));
		when(riskDetectionClient.detect(any(), any()))
			.thenReturn(readResponse());

		executionService.execute(TEACHER_ID, "tn_demo_teacher", runId);

		ArgumentCaptor<AiDetectionRequestHeaders> headers =
			ArgumentCaptor.forClass(AiDetectionRequestHeaders.class);
		verify(riskDetectionClient).detect(any(AiDetectionRequest.class), headers.capture());
		assertThat(headers.getValue().tenantId()).isEqualTo("tn_demo_teacher");
		assertThat(headers.getValue().idempotencyKey().value())
			.isEqualTo("tn_demo_teacher:2026-07-28");
		assertThat(headers.getValue().requestId()).isNotBlank();

		DetectionRun run = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(run.attempts()).singleElement().satisfies(attempt ->
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.SUCCEEDED)
		);
		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			))
			.hasSize(2);
	}

	@Test
	void recordsHttpFailureWithoutStoringSignals() throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000321");
		prepareRun(runId, LocalDate.of(2026, 7, 29));
		when(riskDetectionClient.detect(any(), any()))
			.thenThrow(RiskDetectionClientException.httpError(503, null));

		assertThatThrownBy(() ->
			executionService.execute(TEACHER_ID, "tn_demo_teacher", runId)
		).isInstanceOf(RiskDetectionClientException.class);

		DetectionRun run = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.FAILED);
		assertThat(run.errorCode()).isEqualTo("HTTP_ERROR");
		assertThat(run.attempts()).singleElement().satisfies(attempt -> {
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.FAILED);
			assertThat(attempt.httpStatus()).isEqualTo(503);
			assertThat(attempt.errorCode()).isEqualTo("HTTP_ERROR");
		});
		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			))
			.isEmpty();
	}

	@Test
	void rejectsTheWholeAiResponseAndRecordsFailureWhenOneSignalHasNoEvidence()
		throws IOException {
		UUID runId = UUID.fromString("019846dc-7c00-7000-8000-000000000331");
		prepareRun(runId, LocalDate.of(2026, 7, 30));
		AiDetectionResponse response = readResponse();
		AiDetectionResponse.Signal valid = response.data().signals().getFirst();
		AiDetectionResponse.Signal invalid = copyWithEvidence(valid, List.of());
		when(riskDetectionClient.detect(any(), any())).thenReturn(
			new AiDetectionResponse(
				new AiDetectionResponse.Data(
					List.of(valid, invalid),
					response.data().stats()
				),
				response.error(),
				response.meta()
			)
		);

		// signal을 하나씩 저장하면 첫 결과만 남을 수 있다. 이 테스트는 응답 전체
		// 검증과 저장 트랜잭션이 부분 위험 신호를 만들지 못하게 막는지 확인한다.
		assertThatThrownBy(() ->
			executionService.execute(TEACHER_ID, "tn_demo_teacher", runId)
		).isInstanceOf(DetectionExecutionException.class);

		DetectionRun run = runRepository.findByIdAndTeacherId(runId, TEACHER_ID)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.FAILED);
		assertThat(run.errorCode()).isEqualTo("RESPONSE_PROCESSING_ERROR");
		assertThat(run.attempts()).singleElement().satisfies(attempt -> {
			assertThat(attempt.status())
				.isEqualTo(DetectionRequestAttemptStatus.FAILED);
			assertThat(attempt.errorCode()).isEqualTo("RESPONSE_PROCESSING_ERROR");
		});
		assertThat(signalResultRepository
			.findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
				runId,
				TEACHER_ID
			))
			.isEmpty();
	}

	private void prepareRun(UUID runId, LocalDate analysisDate) throws IOException {
		DetectionRun run = DetectionRun.prepare(
			runId,
			TEACHER_ID,
			analysisDate,
			LocalDate.of(2026, 7, 20),
			"tn_demo_teacher:" + analysisDate,
			"sha256:0123456789abcdef0123456789abcdef"
				+ "0123456789abcdef0123456789abcdef",
			readResource("ai/detect-contract-request.json"),
			Instant.parse("2026-07-27T17:09:59Z")
		);
		runRepository.saveAndFlush(run);
	}

	private AiDetectionResponse readResponse() throws IOException {
		try (var input = new ClassPathResource(
			"ai/detect-contract-response.json"
		).getInputStream()) {
			return objectMapper.readValue(input, AiDetectionResponse.class);
		}
	}

	private AiDetectionResponse.Signal copyWithEvidence(
		AiDetectionResponse.Signal signal,
		List<AiDetectionResponse.Evidence> evidence
	) {
		return new AiDetectionResponse.Signal(
			signal.signalId(),
			signal.studentRef(),
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

	private String readResource(String path) throws IOException {
		return new ClassPathResource(path)
			.getContentAsString(StandardCharsets.UTF_8);
	}
}
