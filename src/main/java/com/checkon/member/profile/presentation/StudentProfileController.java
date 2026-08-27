package com.checkon.member.profile.presentation;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.profile.application.StudentProfileService;
import com.checkon.member.profile.application.dto.NotificationPreferenceRequest;
import com.checkon.member.profile.application.dto.StudentProfileResponse;

/**
 * 학생 프로필 API — 계약 operationId:
 * <ul>
 *   <li>{@code getStudentProfile} — {@code GET /profile}</li>
 *   <li>{@code updateStudentNotificationPreference} — {@code PATCH /profile/notification-preference}</li>
 * </ul>
 *
 * <p>🔴 역할별 폴더를 만들지 않고 <b>파일명으로 가른다</b>(설계 §3-1). 서비스는 subject 만 보고
 * 누가 부르는지 모른다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/students/me/profile")
public class StudentProfileController {

	private final StudentProfileService service;

	public StudentProfileController(StudentProfileService service) {
		this.service = service;
	}

	@GetMapping
	public MemberResponse<StudentProfileResponse> getStudentProfile(
		@CurrentMember MemberSubject subject
	) {
		return MemberResponse.of(service.getProfile(subject));
	}

	@PatchMapping("/notification-preference")
	public ResponseEntity<Void> updateStudentNotificationPreference(
		@CurrentMember MemberSubject subject,
		@RequestBody(required = false) NotificationPreferenceRequest request
	) {
		if (request == null || request.enabled() == null) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"enabled is required",
				List.of(new FieldViolation("enabled", "enabled is required")));
		}
		service.updateNotificationPreference(subject, request.enabled());
		return ResponseEntity.noContent().build();
	}
}
