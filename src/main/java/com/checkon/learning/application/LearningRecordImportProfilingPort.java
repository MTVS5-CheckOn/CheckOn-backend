package com.checkon.learning.application;

// AI가 원본 파일을 프로파일링하고 매핑을 제안하는 외부 경계(LR-003, LR-005).
// AI는 확정된 매핑으로 전체 행을 변환한 결과나 output_url을 반환하지 않는다 —
// 전체 행 변환·검증·저장은 항상 백엔드가 소유한다.
public interface LearningRecordImportProfilingPort {
	LearningRecordImportMappingProposal proposeMapping(LearningRecordImportProfilingRequest request);
}
