package com.checkon.learning.application;

// LR-003(CONFIRMED)의 5단계 흐름만 옮긴 것이다. 부분 성공 등 세분화된 상태는
// LR-008(OPEN)에서 아직 결정되지 않아 COMPLETED/FAILED 두 종단 상태만 둔다.
public enum LearningRecordImportJobPhase {
	UPLOADED,
	MAPPING_PROPOSED,
	MAPPING_CONFIRMED,
	PROCESSING,
	COMPLETED,
	FAILED
}
