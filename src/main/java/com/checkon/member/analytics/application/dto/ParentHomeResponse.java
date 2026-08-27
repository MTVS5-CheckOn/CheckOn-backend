package com.checkon.member.analytics.application.dto;

import java.util.List;

import com.checkon.member.analytics.domain.HomeMetric;
import com.checkon.member.membership.application.ChildView;
import com.checkon.member.report.application.dto.ReportSummaryResponse;

/**
 * {@code GET /member/parents/me/children/{studentId}/home} 응답.
 * 계약 {@code ParentHome}(member-api.yaml:2130-2151) 과 1:1.
 *
 * <p>🔴 <b>왜 다른 sub-context 의 타입을 그대로 담는가</b> — 계약이 {@code ParentHome} 안에
 * {@code Child}·{@code ReportSummary}·{@code LearningRecordSummary} 라는 <b>같은 스키마</b>를
 * 박아 뒀기 때문이다. 여기서 같은 모양의 record 를 새로 선언하면 계약 정본이 둘이 되고,
 * 한쪽만 고쳐지는 순간 홈과 다른 화면의 JSON 이 갈린다(§3-13 「정본이 둘이 되면 반드시 갈린다」).
 * 그래서 <b>모양을 베끼지 않고 타입을 재사용</b>한다.</p>
 *
 * <p>🔴 그 대가로 이 record 와 {@code ParentHomeController} 두 파일이 sub-context 경계를
 * 넘는다. 조립은 <b>presentation 한 곳</b>에서만 하고, analytics 의 어떤 서비스도
 * {@code membership}·{@code report} 를 부르지 않는다 — 경계를 넘는 지점을 세어서 볼 수 있게
 * 두 개로 묶어 둔 것이다(설계 §3-1).</p>
 *
 * <p>🔴 {@code latestReport} 는 계약이 {@code nullable: true} 라 <b>키가 존재하고 값이
 * {@code null}</b> 이어야 한다. 「null 이면 키를 뺀다」를 record 전체에 걸지 않는다
 * (코드 규칙 §11-3).</p>
 *
 * @param recentRecords 계약이 {@code required} 도 {@code nullable} 도 아니지만 항상 채운다 —
 *                      기록이 없으면 빈 배열이다. 🔴 <b>빈 배열</b>과 <b>지표의 NO_DATA</b> 는
 *                      다른 뜻이다(분기표 §0-2 마지막 줄)
 */
public record ParentHomeResponse(
	ChildView child,
	List<HomeMetric> metrics,
	ReportSummaryResponse latestReport,
	List<LearningRecordResponse> recentRecords
) {
}
