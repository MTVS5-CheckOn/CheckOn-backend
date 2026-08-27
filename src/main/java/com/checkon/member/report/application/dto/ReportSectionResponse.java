package com.checkon.member.report.application.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 계약 {@code ReportDetail.sections[]}(member-api.yaml:2265-2288).
 *
 * <p>🔴 {@code data} 는 저장된 JSONB 를 <b>그대로</b> 되돌린다. 조회 시점에 재계산하거나
 * PR7 집계와 대조해 보정하지 않는다 — 발행 스냅샷과 현재 집계가 다른 것은 설계다.</p>
 *
 * <p>🔴 {@code title} 은 계약에서 {@code nullable} 이 아니고 {@code required} 도 아니다 →
 * 값이 없으면 <b>키를 뺀다</b>(코드 규칙 §11-3). {@code body}·{@code data}·
 * {@code unproducedReason} 은 {@code nullable: true} → <b>키를 두고 값을 null</b> 로 둔다.</p>
 */
public record ReportSectionResponse(
	String kind,
	String title,
	String status,
	String body,
	JsonNode data,
	List<String> evidenceRefs,
	String unproducedReason
) {
}
