package com.checkon.problem.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.problem.application.CreateProblemGenerationCommand;
import com.checkon.problem.application.ProblemGenerationRequestService;
import com.checkon.problem.application.ProblemGenerationRequestView;
import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.domain.ProblemTargetKind;
import com.checkon.problem.domain.ProblemTypeTag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/problem-requests")
public class ProblemGenerationController {

	private final ProblemGenerationRequestService service;
	private final ObjectMapper objectMapper;

	public ProblemGenerationController(
		ProblemGenerationRequestService service,
		ObjectMapper objectMapper
	) {
		this.service = service;
		this.objectMapper = objectMapper;
	}

	@PostMapping
	public ResponseEntity<ProblemGenerationResponse> create(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
		@Valid @RequestBody ProblemGenerationRequest request
	) {
		var creation = service.create(
			teacherProfileId(principal),
			request.toCommand(idempotencyKey)
		);
		var response = toResponse(service.get(teacherProfileId(principal), creation.requestId()), !creation.created());
		return ResponseEntity.accepted()
			.location(URI.create("/api/v1/problem-requests/" + creation.requestId()))
			.body(response);
	}

	@GetMapping("/{requestId}")
	public ProblemGenerationResponse get(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID requestId
	) {
		return toResponse(service.get(teacherProfileId(principal), requestId), false);
	}

	private ProblemGenerationResponse toResponse(ProblemGenerationRequestView view, boolean replayed) {
		return new ProblemGenerationResponse(
			view.id(), view.targetKind(), view.status(), view.aiJobId(), view.aiExecutionId(),
			view.aiSetId(), view.aiResultStatus(), view.errorCode(), readJson(view.resultPayload()),
			readJson(view.versionsPayload()), view.requestedAt(), view.dispatchedAt(),
			view.completedAt(), replayed
		);
	}

	private JsonNode readJson(String value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.readTree(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("stored problem generation JSON is invalid", exception);
		}
	}

	private static UUID teacherProfileId(AuthenticatedAccount principal) {
		return principal == null ? null : principal.teacherProfileId();
	}

	public record ProblemGenerationRequest(
		@NotNull ProblemTargetKind targetKind,
		@NotNull UUID targetId,
		@NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 120) String> manualTargets,
		@NotBlank @Size(max = 80) String taxonomyVersion,
		@NotEmpty @Size(max = 4) List<@NotNull ProblemTypeTag> typeTags,
		@Min(1) @Max(10) int count,
		ProblemDifficulty requestedDifficulty
	) {
		CreateProblemGenerationCommand toCommand(String idempotencyKey) {
			return new CreateProblemGenerationCommand(
				targetKind, targetId, manualTargets, taxonomyVersion, typeTags,
				count, requestedDifficulty, idempotencyKey
			);
		}
	}

	public record ProblemGenerationResponse(
		UUID requestId,
		ProblemTargetKind targetKind,
		ProblemGenerationStatus status,
		String jobId,
		String executionId,
		String setId,
		String resultStatus,
		String errorCode,
		JsonNode result,
		JsonNode versions,
		Instant requestedAt,
		Instant dispatchedAt,
		Instant completedAt,
		boolean replayed
	) { }
}
