package com.checkon.member.auth.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.auth.application.MemberSessionService;
import com.checkon.member.auth.application.MemberSessionView;
import com.checkon.member.auth.application.StudentActivationView;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;

/**
 * 세션 bootstrap 과 활성화 상태 폴링.
 *
 * <p>🔴 이 두 경로는 대기 학생({@code PENDING_PARENT_LINK})도 부를 수 있다 — MB-02 CONFIRMED.
 * 허용은 {@code StudentActivationGuard.PENDING_STUDENT_ALLOWED} 한 곳에서만 정의한다.
 * 여기서 다시 분기하면 판정이 두 곳으로 갈린다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/auth")
public class MemberSessionController {

	private final MemberSessionService sessionService;

	public MemberSessionController(MemberSessionService sessionService) {
		this.sessionService = sessionService;
	}

	@GetMapping("/session")
	public MemberResponse<MemberSessionView> getMemberSession(
		@CurrentMember MemberSubject subject
	) {
		return MemberResponse.of(sessionService.session(subject));
	}

	@GetMapping("/students/activation-status")
	public MemberResponse<StudentActivationView> getStudentActivationStatus(
		@CurrentMember MemberSubject subject
	) {
		return MemberResponse.of(sessionService.activationStatus(subject));
	}
}
