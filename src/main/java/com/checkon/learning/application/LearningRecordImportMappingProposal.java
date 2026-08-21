package com.checkon.learning.application;

import java.util.Map;

// 매핑 저장 스키마와 source_fingerprint 계산 규칙은 LR-006(PARTIAL)에서 아직
// 확정되지 않았다. suggestedFieldMappings는 원본 컬럼 -> 표준 필드 이름의
// 자리표시자 표현이며, 신뢰도·모호한 매핑 등 LR-007이 요구하는 값은 비어 있다.
public record LearningRecordImportMappingProposal(Map<String, String> suggestedFieldMappings) {
}
