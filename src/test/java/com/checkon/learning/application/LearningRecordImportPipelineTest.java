package com.checkon.learning.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

// Import 파이프라인은 아직 정책이 확정되지 않은 골격이다. 이 테스트는 각 단계가
// 실제로 동작하는 것처럼 보이지 않도록, 의도한 대로 막혀 있는지만 확인한다.
class LearningRecordImportPipelineTest {

	private static final UUID JOB_ID = UUID.fromString("0198a000-0000-7000-8000-000000000009");

	private final LearningRecordImportProfilingPort profilingPort =
		mock(LearningRecordImportProfilingPort.class);
	private final RegisterLearningRecordService recordRegistration =
		mock(RegisterLearningRecordService.class);
	private final LearningRecordImportPipeline pipeline =
		new LearningRecordImportPipeline(profilingPort, recordRegistration);

	@Test
	void uploadIsBlockedByOpenFileContractPolicy() {
		assertThatThrownBy(() -> pipeline.upload("placeholder"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("LR-009");
	}

	@Test
	void requestMappingIsBlockedByMissingJobStorage() {
		assertThatThrownBy(() -> pipeline.requestMapping(JOB_ID))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("LR-006");
		verifyNoInteractions(profilingPort);
	}

	@Test
	void confirmMappingIsBlockedByOpenMappingStorageContract() {
		assertThatThrownBy(() -> pipeline.confirmMapping(JOB_ID, Map.of()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("LR-007");
	}

	@Test
	void executeIsBlockedByOpenRowValidationAndResultPolicy() {
		assertThatThrownBy(() -> pipeline.execute(JOB_ID))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("LR-008");
		verifyNoInteractions(recordRegistration);
	}
}
