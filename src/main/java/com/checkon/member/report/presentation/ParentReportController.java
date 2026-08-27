package com.checkon.member.report.presentation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.report.application.ParentReportQueryService;
import com.checkon.member.report.application.ReportFileAccessService;
import com.checkon.member.report.application.dto.ReportDetailResponse;
import com.checkon.member.report.application.dto.ReportFileAccessResponse;
import com.checkon.member.report.application.dto.ReportListPage;

/**
 * 학부모의 발행 보고서 조회와 PDF 열람 URL 발급.
 *
 * <p>🔴 자녀 관계와 강사 교집합 재검증은 서비스가 매 요청마다 {@code ParentChildAccessGuard} 로
 * 한다. 관계 없음·미발행·연결 안 된 강사는 전부 <b>{@code 404 RESOURCE_NOT_FOUND}</b> 다.</p>
 *
 * <p>🔴 {@code limit} 상한 초과는 조용히 깎지 않고 {@code 400} 이다(분기표 §0-5).
 * 그래서 컨트롤러에 {@code defaultValue} 를 두지 않고 {@code null} 을 그대로 서비스에 넘긴다 —
 * 기본값도 상한 판정도 한 곳({@code MemberReportProperties})에 있어야 갈리지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/parents/me/children/{studentId}/reports")
public class ParentReportController {

	private final ParentReportQueryService queryService;
	private final ReportFileAccessService fileAccessService;

	public ParentReportController(
		ParentReportQueryService queryService,
		ReportFileAccessService fileAccessService
	) {
		this.queryService = queryService;
		this.fileAccessService = fileAccessService;
	}

	@GetMapping
	public MemberResponse<ReportListPage> list(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@RequestParam(value = "teacherId", required = false) UUID teacherId,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", required = false) Integer limit
	) {
		return MemberResponse.of(
			queryService.list(subject, studentId, teacherId, cursor, limit));
	}

	@GetMapping("/{reportId}")
	public MemberResponse<ReportDetailResponse> detail(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@PathVariable UUID reportId
	) {
		return MemberResponse.of(queryService.detail(subject, studentId, reportId));
	}

	/**
	 * 🔴 {@code Idempotency-Key} 는 계약상 <b>선택</b>이다(member-api.yaml:1110).
	 * 발급은 부수효과가 없는 순수 계산이라(토큰은 서명일 뿐 DB 에 안 남는다) 재발급이
	 * 안전하다 — 그래서 멱등 저장을 걸지 않는다. 걸면 같은 키로 <b>이미 만료된</b> URL 이
	 * 그대로 재생되어 오히려 계약을 어긴다.
	 */
	@PostMapping("/{reportId}/file-access")
	@ResponseStatus(HttpStatus.CREATED)
	public MemberResponse<ReportFileAccessResponse> issueFileAccess(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@PathVariable UUID reportId
	) {
		return MemberResponse.of(fileAccessService.issue(subject, studentId, reportId));
	}
}
