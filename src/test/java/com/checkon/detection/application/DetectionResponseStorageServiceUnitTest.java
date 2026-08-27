package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.infrastructure.persistence.DetectionSignalResultRepository;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.engagement.application.EngagementCandidateService;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DetectionResponseStorageServiceUnitTest {

	private static final UUID TEACHER_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000002");
	private static final UUID RUN_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000401");
	private static final UUID ATTEMPT_ID =
		UUID.fromString("019846dc-7c00-7000-8000-000000000402");

	@Test
	void rejectsUnsupportedSignalTypeBeforePersistingAnyResult() throws Exception {
		ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
		String snapshot = new ClassPathResource("ai/detect-contract-request.json")
			.getContentAsString(StandardCharsets.UTF_8);
		DetectionRun run = DetectionRun.prepare(
			RUN_ID,
			TEACHER_ID,
			LocalDate.of(2026, 8, 25),
			LocalDate.of(2026, 8, 24),
			"tenant:2026-08-25",
			"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			snapshot,
			Instant.parse("2026-08-25T00:00:00Z")
		);
		run.startAttempt(
			ATTEMPT_ID,
			"request-unsupported-signal",
			Instant.parse("2026-08-25T00:00:01Z")
		);

		AiDetectionResponse base = objectMapper.readValue(
			new ClassPathResource("ai/detect-contract-response.json").getInputStream(),
			AiDetectionResponse.class
		);
		AiDetectionResponse.Signal source = base.data().signals().getFirst();
		AiDetectionResponse.Signal unsupported = new AiDetectionResponse.Signal(
			source.signalId(), source.studentRef(), source.classRef(), source.ruleId(),
			"learning_gap", source.displayLabel(), source.metric(), source.observed(),
			source.baseline(), source.sampleSize(), source.score(), source.rank(),
			source.advisory(), source.lifecycle(), source.brief(), source.evidence()
		);
		AiDetectionResponse response = new AiDetectionResponse(
			new AiDetectionResponse.Data(List.of(unsupported), base.data().stats()),
			base.error(),
			base.meta()
		);

		DetectionRunRepository runs = mock(DetectionRunRepository.class);
		DetectionSignalResultRepository results = mock(DetectionSignalResultRepository.class);
		DetectionIdGenerator ids = mock(DetectionIdGenerator.class);
		TeacherTenantDatabaseContext tenantContext = mock(
			TeacherTenantDatabaseContext.class
		);
		EngagementCandidateService candidates = mock(EngagementCandidateService.class);
		when(runs.findByIdAndTeacherId(RUN_ID, TEACHER_ID)).thenReturn(Optional.of(run));
		DetectionResponseStorageService service = new DetectionResponseStorageService(
			runs, results, ids, objectMapper, tenantContext, candidates
		);

		assertThatThrownBy(() -> service.storeSuccessfulResponse(
			TEACHER_ID,
			RUN_ID,
			ATTEMPT_ID,
			200,
			response,
			Instant.parse("2026-08-25T00:00:02Z")
		))
			.isInstanceOf(DetectionResponseStorageException.class)
			.hasMessageContaining("Unsupported AI signal_type: learning_gap");
		verifyNoInteractions(results, ids, candidates);
	}
}
