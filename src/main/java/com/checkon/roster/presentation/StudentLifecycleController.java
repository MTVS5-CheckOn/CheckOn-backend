package com.checkon.roster.presentation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.roster.application.StudentLifecycleService;
import com.checkon.roster.domain.RelationshipStatus;

@RestController
@RequestMapping("/api/v1/students")
public class StudentLifecycleController {

	private final StudentLifecycleService service;

	public StudentLifecycleController(StudentLifecycleService service) {
		this.service = service;
	}

	@PostMapping("/{studentId}/pause")
	public Response pause(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID studentId
	) {
		return Response.from(service.pause(principal, studentId));
	}

	@PostMapping("/{studentId}/return")
	public Response resume(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID studentId
	) {
		return Response.from(service.resume(principal, studentId));
	}

	public record Response(UUID studentId, RelationshipStatus status, Instant occurredAt) {
		private static Response from(StudentLifecycleService.StudentLifecycleView view) {
			return new Response(view.studentId(), view.status(), view.occurredAt());
		}
	}
}
