package com.checkon.member.report.domain;

import java.util.List;

/**
 * 발행 스냅샷의 섹션 한 개.
 *
 * <p>🔴 {@code content} 는 저장된 JSONB 원문 문자열을 <b>그대로</b> 들고 다닌다. 조회 시점에
 * 재계산하거나 PR7 집계와 대조해 보정하지 않는다 — 발행 스냅샷과 현재 집계가 다른 것은 버그가
 * 아니라 설계다(지시서 §3).</p>
 *
 * <p>🔴 {@code kind} 에 enum 을 두지 않는다. 계약의 {@code kind} 는 enum 없는 문자열이고
 * 어휘 확정은 MB-53 이다. 없는 어휘를 지어내지 않는다.</p>
 *
 * @param status {@code AVAILABLE | INSUFFICIENT | NO_DATA | NOT_PRODUCED}
 * @param unproducedReason {@code NOT_PRODUCED} 일 때만 값이 있다. V45 CHECK 가 강제한다
 */
public record PublishedReportSection(
	String kind,
	String title,
	int ordinal,
	String status,
	String body,
	String content,
	List<String> evidenceRefs,
	String unproducedReason
) {
}
