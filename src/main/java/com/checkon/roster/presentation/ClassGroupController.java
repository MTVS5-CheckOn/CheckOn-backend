package com.checkon.roster.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.global.presentation.PagedResponse;
import com.checkon.roster.application.ClassManagementService;
import com.checkon.roster.application.ClassManagementService.ClassPage;
import com.checkon.roster.application.ClassManagementService.ClassView;
import com.checkon.roster.domain.ClassGroupStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassGroupController {
	private final ClassManagementService service;

	public ClassGroupController(ClassManagementService service) {
		this.service = service;
	}

	@GetMapping
	PagedResponse<ClassResponse> list(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		ClassPage result = service.list(principal, page, size);
		return PagedResponse.of(
			result.content().stream().map(ClassResponse::from).toList(),
			result.page(),
			result.size(),
			result.totalElements()
		);
	}

	@PostMapping
	ResponseEntity<ClassResponse> create(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@Valid @RequestBody DetailsRequest request
	) {
		ClassView created = service.create(
			principal, request.name(), request.subject(), request.memo()
		);
		return ResponseEntity.created(
			URI.create("/api/v1/classes/" + created.classId())
		).body(ClassResponse.from(created));
	}

	@GetMapping("/{classId}")
	ClassResponse detail(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID classId
	) {
		return ClassResponse.from(service.detail(principal, classId));
	}

	@PatchMapping("/{classId}")
	ClassResponse update(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID classId,
		@Valid @RequestBody DetailsRequest request
	) {
		return ClassResponse.from(service.update(
			principal, classId, request.name(), request.subject(), request.memo()
		));
	}

	@PostMapping("/{classId}/archive")
	ClassResponse archive(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID classId
	) {
		return ClassResponse.from(service.archive(principal, classId));
	}

	public record DetailsRequest(
		@NotBlank String name,
		@NotBlank String subject,
		@Size(max = 1000) String memo
	) {
	}

	public record ClassResponse(
		UUID classId,
		String name,
		String subject,
		String memo,
		ClassGroupStatus status,
		long activeStudentCount,
		Instant createdAt,
		Instant updatedAt
	) {
		static ClassResponse from(ClassView view) {
			return new ClassResponse(
				view.classId(),
				view.name(),
				view.subject(),
				view.memo(),
				view.status(),
				view.activeStudentCount(),
				view.createdAt(),
				view.updatedAt()
			);
		}
	}
}
