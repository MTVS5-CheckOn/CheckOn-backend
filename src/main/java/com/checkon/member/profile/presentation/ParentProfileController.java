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
import com.checkon.member.profile.application.ParentProfileService;
import com.checkon.member.profile.application.dto.NotificationPreferenceRequest;
import com.checkon.member.profile.application.dto.ParentProfileResponse;

@RestController
@RequestMapping("/api/v1/member/parents/me/profile")
public class ParentProfileController {

	private final ParentProfileService service;

	public ParentProfileController(ParentProfileService service) {
		this.service = service;
	}

	@GetMapping
	public MemberResponse<ParentProfileResponse> getParentProfile(
		@CurrentMember MemberSubject subject
	) {
		return MemberResponse.of(service.getProfile(subject));
	}

	@PatchMapping("/notification-preference")
	public ResponseEntity<Void> updateParentNotificationPreference(
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
