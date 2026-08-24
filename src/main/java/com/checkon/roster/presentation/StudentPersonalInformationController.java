package com.checkon.roster.presentation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.roster.application.SaveStudentPersonalInformationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/students")
public class StudentPersonalInformationController {
	private final SaveStudentPersonalInformationService service;

	public StudentPersonalInformationController(
		SaveStudentPersonalInformationService service
	) {
		this.service = service;
	}

	@PutMapping("/{studentId}/personal-information/name")
	public Response saveName(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID studentId,
		@Valid @RequestBody Request request
	) {
		var result = service.save(principal, studentId, request.studentName());
		return new Response(result.studentId(), result.studentName(), result.updatedAt());
	}

	public record Request(
		@NotBlank @Size(max = 100) String studentName
	) {
	}

	public record Response(UUID studentId, String studentName, Instant updatedAt) {
	}
}

