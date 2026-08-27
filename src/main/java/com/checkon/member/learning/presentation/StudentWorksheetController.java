package com.checkon.member.learning.presentation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.presentation.CursorPage;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.learning.application.StudentWorksheetService;
import com.checkon.member.learning.application.dto.WorksheetDetailResponse;
import com.checkon.member.learning.application.dto.WorksheetSummaryResponse;

/**
 * 학생의 학습지 목록·상세. 계약 {@code operationId} 와 1:1 이다:
 * <ul>
 *   <li>{@code listStudentWorksheets} — {@code GET /member/students/me/worksheets}</li>
 *   <li>{@code getStudentWorksheet} — 상세 (파라미터 {@code assignmentId})</li>
 * </ul>
 *
 * <p>🔴 역할은 <b>파일명</b>으로 가른다 — 폴더를 역할로 쪼개지 않는다(설계 §3-1). 이 컨트롤러는
 * 학생 화면만 부른다. 학부모 화면은 별도 컨트롤러(추후 PR6/PR7)에서 같은 서비스를 재사용한다.</p>
 *
 * <p>🔴 대기 학생({@code PENDING_PARENT_LINK}) 은 이 두 오퍼레이션이 <b>허용 목록에 없다</b>
 * (분기표 §0-3) — 진입을 막는 것은 {@code StudentActivationGuard} 이며 컨트롤러는 판정하지 않는다.
 * 여기서 다시 체크하면 판정이 두 곳에 흩어져 갈릴 수 있다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/students/me/worksheets")
public class StudentWorksheetController {

	private final StudentWorksheetService worksheetService;

	public StudentWorksheetController(StudentWorksheetService worksheetService) {
		this.worksheetService = worksheetService;
	}

	@GetMapping
	public MemberResponse<CursorPage<WorksheetSummaryResponse>> listStudentWorksheets(
		@CurrentMember MemberSubject subject,
		@RequestParam(name = "cursor", required = false) String cursor,
		@RequestParam(name = "limit", required = false) Integer limit,
		@RequestParam(name = "status", required = false) String status
	) {
		return MemberResponse.of(
			worksheetService.listWorksheets(subject, cursor, limit, status));
	}

	@GetMapping("/{assignmentId}")
	public MemberResponse<WorksheetDetailResponse> getStudentWorksheet(
		@CurrentMember MemberSubject subject,
		@PathVariable("assignmentId") UUID assignmentId
	) {
		return MemberResponse.of(worksheetService.getWorksheet(subject, assignmentId));
	}
}
