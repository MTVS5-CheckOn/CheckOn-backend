package com.checkon.member.analytics.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.analytics.application.ParentHomeService;
import com.checkon.member.analytics.application.dto.ParentHomeResponse;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.membership.application.ChildQueryService;
import com.checkon.member.membership.application.ChildView;
import com.checkon.member.report.application.ParentReportQueryService;
import com.checkon.member.report.application.dto.ReportSummaryResponse;

/**
 * 자녀 홈 요약 — 학부모 앱의 <b>첫 화면</b>이다. 학생판은
 * {@code member/learning/presentation/StudentHomeController} 이고 같은 형태다.
 *
 * <p>🔴 <b>이 컨트롤러가 조립 지점(composition root)이다.</b> 홈은 네 sub-context 의 조각을
 * 한 화면에 모은다 — {@code membership}(자녀) · {@code analytics}(지표·최근 기록) ·
 * {@code report}(최근 발행 보고서). CLAUDE.md §1-6 이 「sub-context 끼리 직접 참조 금지」라
 * 규정하므로, 경계를 넘는 곳을 <b>presentation 한 곳</b>으로 모았다.
 * analytics 의 어떤 application 서비스도 {@code membership}·{@code report} 를 부르지 않는다.</p>
 *
 * <p>🔴 <b>새로 계산하는 것이 없다.</b> 네 조각 전부 기존 서비스가 이미 내려보내던 값이다 —
 * 홈이 자기 산식이나 자기 필터를 가지면 다른 화면과 값이 갈린다.</p>
 *
 * <p>🔴 특히 {@code latestReport} 는 PR9 의 발행 보고서 조회 경로를 <b>그대로</b> 탄다
 * ({@code limit=1}). 미발행 은닉은 V45 의 스키마·RLS 가 보장하므로 여기서 필터를 새로 쓰면
 * 은닉이 두 벌이 되고 갈린다.</p>
 *
 * <p>🔴 관계 없음·관계 종료·강사 필터 불일치는 전부 <b>404 RESOURCE_NOT_FOUND</b> 다
 * (설계 §6-4 불변식 3). 403 이 아니다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/parents/me/children/{studentId}/home")
public class ParentHomeController {

	/** 🔴 최근 발행 보고서 <b>한 건</b>. 목록 경로를 그대로 쓰고 첫 원소만 본다. */
	private static final int LATEST_REPORT_LIMIT = 1;

	private final ParentHomeService homeService;
	private final ChildQueryService childQueryService;
	private final ParentReportQueryService reportQueryService;

	public ParentHomeController(
		ParentHomeService homeService,
		ChildQueryService childQueryService,
		ParentReportQueryService reportQueryService
	) {
		this.homeService = homeService;
		this.childQueryService = childQueryService;
		this.reportQueryService = reportQueryService;
	}

	@GetMapping
	public MemberResponse<ParentHomeResponse> getParentChildHome(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@RequestParam(value = "teacherId", required = false) UUID teacherId
	) {
		// 🔴 관계·강사 필터를 먼저 판정한다. 여기서 걸리면 아래 조회는 돌지 않는다 —
		//    부재를 확인시켜 주는 응답 시간 차이를 만들지 않는다.
		homeService.requireChildAccess(subject, studentId, teacherId);
		return MemberResponse.of(new ParentHomeResponse(
			child(subject, studentId),
			homeService.metrics(subject, studentId),
			latestReport(subject, studentId, teacherId),
			homeService.recentRecords(subject, studentId)));
	}

	/**
	 * 🔴 {@code ChildQueryService} 는 <b>활성</b> 자녀만 돌려준다. 그래서 연결 종료된 자녀는
	 * 여기서도 없는 것이고 404 다 — {@code requireChildAccess} 와 <b>같은 판정</b>이 두 번
	 * 나는 것이 아니라, 관계 테이블 하나를 두 경로가 같은 기준으로 읽는 것이다.
	 */
	private ChildView child(MemberSubject subject, UUID studentId) {
		return childQueryService.listChildren(subject).stream()
			.filter(view -> studentId.equals(view.studentId()))
			.findFirst()
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "child home not found"));
	}

	/** 🔴 발행된 것만 나온다. 없으면 {@code null} 이고 계약이 그것을 요구한다(nullable). */
	private ReportSummaryResponse latestReport(
		MemberSubject subject, UUID studentId, UUID teacherId
	) {
		List<ReportSummaryResponse> items = reportQueryService
			.list(subject, studentId, teacherId, null, LATEST_REPORT_LIMIT)
			.items();
		return items.isEmpty() ? null : items.get(0);
	}
}
