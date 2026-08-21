package com.checkon.learning.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

// LR-003(CONFIRMED)의 책임 경계만 반영한 골격이다: 파일 업로드 -> AI 매핑 제안
// -> 강사 검토·수정 -> 백엔드 확정 -> 백엔드 전체 행 변환·검증·저장.
// 각 단계의 요청/응답 형식, Import 잡 저장 모델, 오류 코드는 LR-004, LR-006~009가
// 사용자 승인으로 CONFIRMED 되기 전까지 구현하지 않는다 (docs/POLICY_REGISTER.md 참고).
@Service
public class LearningRecordImportPipeline {
	private final LearningRecordImportProfilingPort profilingPort;
	private final RegisterLearningRecordService recordRegistration;

	public LearningRecordImportPipeline(
		LearningRecordImportProfilingPort profilingPort,
		RegisterLearningRecordService recordRegistration
	) {
		this.profilingPort = profilingPort;
		this.recordRegistration = recordRegistration;
	}

	// 1. 파일 업로드: 지원 형식·크기 제한·저장 위치가 LR-009에서 열려 있다.
	public LearningRecordImportJobPhase upload(String fileReference) {
		throw blockedBy("LR-009");
	}

	// 2. AI 매핑 제안 요청: Import 잡 저장 모델이 없어 fileReference를 조회할 수 없다.
	public LearningRecordImportMappingProposal requestMapping(UUID jobId) {
		throw blockedBy("LR-006, LR-009");
	}

	// 3~4. 강사 검토·수정 결과의 백엔드 확정: 저장 필드와 확정 API 계약이 없다.
	public LearningRecordImportJobPhase confirmMapping(
		UUID jobId,
		Map<String, String> teacherApprovedMapping
	) {
		throw blockedBy("LR-006, LR-007");
	}

	// 5. 전체 행 변환·검증·저장: 최종 저장은 RegisterLearningRecordService(LR-002
	// 경계)를 재사용할 예정이지만, 행별 검증 규칙과 결과 집계 계약(LR-004, LR-007,
	// LR-008)이 없어 실행할 수 없다.
	public LearningRecordImportJobPhase execute(UUID jobId) {
		throw blockedBy("LR-004, LR-007, LR-008, LR-009");
	}

	private static UnsupportedOperationException blockedBy(String policyIds) {
		return new UnsupportedOperationException(
			"docs/POLICY_REGISTER.md " + policyIds + "이(가) CONFIRMED 되기 전까지 구현하지 않는다"
		);
	}
}
