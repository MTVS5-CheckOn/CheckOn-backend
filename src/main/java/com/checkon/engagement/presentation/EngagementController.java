package com.checkon.engagement.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.engagement.application.EngagementService;
import com.checkon.engagement.application.EngagementService.AlertDetail;
import com.checkon.engagement.application.EngagementService.AlertView;
import com.checkon.engagement.application.EngagementService.InterventionView;
import com.checkon.engagement.application.EngagementService.ReminderView;
import com.checkon.engagement.domain.AlertStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/engagement")
public class EngagementController {
	private final EngagementService engagementService;

	public EngagementController(EngagementService engagementService) {
		this.engagementService = engagementService;
	}

	@GetMapping("/alerts")
	List<AlertView> list(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam(defaultValue = "PENDING_REVIEW") AlertStatus status
	) {
		return engagementService.list(teacherProfileId(principal), status);
	}

	@GetMapping("/alerts/{alertId}")
	AlertDetail detail(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID alertId
	) {
		return engagementService.detail(teacherProfileId(principal), alertId);
	}

	@PostMapping("/alerts/{alertId}/approval")
	AlertView approve(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID alertId
	) {
		return engagementService.approve(teacherProfileId(principal), alertId);
	}

	@PostMapping("/alerts/{alertId}/rejection")
	AlertView reject(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID alertId,
		@Valid @RequestBody RejectionRequest request
	) {
		return engagementService.reject(
			teacherProfileId(principal), alertId, request.note()
		);
	}

	@PostMapping("/alerts/{alertId}/interventions")
	InterventionView createIntervention(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID alertId,
		@Valid @RequestBody InterventionRequest request
	) {
		return engagementService.createIntervention(
			teacherProfileId(principal), alertId, request.type(), request.content()
		);
	}

	@PostMapping("/interventions/{interventionId}/completion")
	InterventionView completeIntervention(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID interventionId
	) {
		return engagementService.finishIntervention(
			teacherProfileId(principal), interventionId, true
		);
	}

	@PostMapping("/interventions/{interventionId}/cancellation")
	InterventionView cancelIntervention(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID interventionId
	) {
		return engagementService.finishIntervention(
			teacherProfileId(principal), interventionId, false
		);
	}

	@PostMapping("/interventions/{interventionId}/reminders")
	ReminderView createReminder(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID interventionId,
		@Valid @RequestBody ReminderRequest request
	) {
		return engagementService.createReminder(
			teacherProfileId(principal), interventionId, request.scheduledAt()
		);
	}

	@PostMapping("/reminders/{reminderId}/completion")
	ReminderView completeReminder(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID reminderId
	) {
		return engagementService.finishReminder(
			teacherProfileId(principal), reminderId, true
		);
	}

	@PostMapping("/reminders/{reminderId}/cancellation")
	ReminderView cancelReminder(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID reminderId
	) {
		return engagementService.finishReminder(
			teacherProfileId(principal), reminderId, false
		);
	}

	private UUID teacherProfileId(AuthenticatedAccount principal) {
		// 요청 본문의 teacherId는 조작할 수 있으므로 테넌트 경계에는 사용하지 않는다.
		return principal == null ? null : principal.teacherProfileId();
	}

	public record RejectionRequest(@NotBlank @Size(max = 2000) String note) {
	}

	public record InterventionRequest(
		@NotBlank @Size(max = 40) String type,
		@NotBlank @Size(max = 4000) String content
	) {
	}

	public record ReminderRequest(@NotNull Instant scheduledAt) {
	}
}
