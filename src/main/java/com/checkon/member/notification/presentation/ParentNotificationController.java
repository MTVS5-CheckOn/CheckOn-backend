package com.checkon.member.notification.presentation;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.presentation.CursorPage;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.notification.application.ParentNotificationService;
import com.checkon.member.notification.application.dto.NotificationResponse;

/**
 * 학부모 알림 API — 계약 operationId 3개:
 * <ul>
 *   <li>{@code listParentNotifications} — {@code GET /notifications}</li>
 *   <li>{@code markParentNotificationRead} — {@code POST /notifications/{id}/read}</li>
 *   <li>{@code markAllParentNotificationsRead} — {@code POST /notifications/read-all}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/member/parents/me/notifications")
public class ParentNotificationController {

	private final ParentNotificationService service;

	public ParentNotificationController(ParentNotificationService service) {
		this.service = service;
	}

	@GetMapping
	public MemberResponse<CursorPage<NotificationResponse>> listParentNotifications(
		@CurrentMember MemberSubject subject,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", required = false) Integer limit
	) {
		return MemberResponse.of(service.list(subject, cursor, limit));
	}

	@PostMapping("/{notificationId}/read")
	public ResponseEntity<Void> markParentNotificationRead(
		@CurrentMember MemberSubject subject,
		@PathVariable("notificationId") UUID notificationId
	) {
		service.markRead(subject, notificationId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/read-all")
	public ResponseEntity<Void> markAllParentNotificationsRead(
		@CurrentMember MemberSubject subject
	) {
		service.markAllRead(subject);
		return ResponseEntity.noContent().build();
	}
}
