package com.checkon.problem.presentation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.global.presentation.PagedResponse;
import com.checkon.problem.application.CreateProblemStudioCommand;
import com.checkon.problem.application.ProblemGenerationRequestService;
import com.checkon.problem.application.ProblemGenerationRevisionService;
import com.checkon.problem.application.ProblemGenerationRevisionService.RevisionView;
import com.checkon.problem.application.ProblemStudioService;
import com.checkon.problem.application.ProblemStudioViews.Assignment;
import com.checkon.problem.application.ProblemStudioViews.Printable;
import com.checkon.problem.application.ProblemStudioViews.Review;
import com.checkon.problem.application.ProblemStudioViews.SavedSet;
import com.checkon.problem.application.ProblemStudioViews.StudentPage;
import com.checkon.problem.application.ProblemStudioViews.StudentSummary;
import com.checkon.problem.application.ProblemStudioViews.WeaknessAnalysis;
import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTypeTag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/problem-studio")
public class ProblemStudioController {
	private final ProblemStudioService studio;
	private final ProblemGenerationRequestService requests;
	private final ProblemGenerationRevisionService revisions;

	public ProblemStudioController(ProblemStudioService studio, ProblemGenerationRequestService requests,
		ProblemGenerationRevisionService revisions) {
		this.studio = studio;
		this.requests = requests;
		this.revisions = revisions;
	}

	@GetMapping("/students")
	public PagedResponse<StudentSummary> students(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		StudentPage result = studio.listStudents(teacherProfileId(principal), page, size);
		return PagedResponse.of(
			result.content(),
			result.page(),
			result.size(),
			result.totalElements()
		);
	}

	@GetMapping("/students/{studentId}/weakness-analysis")
	public WeaknessAnalysis weakness(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID studentId
	) {
		return studio.analyzeWeakness(teacherProfileId(principal), studentId);
	}

	@PostMapping("/requests")
	public ResponseEntity<StudioRequestResponse> create(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
		@Valid @RequestBody StudioRequest request
	) {
		var creation = requests.createStudio(teacherProfileId(principal), request.toCommand(idempotencyKey));
		var current = requests.get(teacherProfileId(principal), creation.requestId());
		return ResponseEntity.accepted()
			.location(URI.create("/api/v1/problem-requests/" + creation.requestId()))
			.body(new StudioRequestResponse(creation.requestId(), current.status().name(), !creation.created()));
	}

	@GetMapping("/requests/{requestId}/review")
	public Review review(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID requestId) {
		return studio.review(teacherProfileId(principal), requestId);
	}

	@PutMapping("/requests/{requestId}/selection")
	public Review select(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID requestId,
		@Valid @RequestBody SelectionRequest request
	) {
		return studio.updateSelection(teacherProfileId(principal), requestId, request.itemIds());
	}

	@PostMapping("/requests/{requestId}/save")
	public SavedSet save(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID requestId) {
		return studio.save(teacherProfileId(principal), requestId);
	}

	@PostMapping("/requests/{requestId}/publish")
	public Assignment publish(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID requestId) {
		return studio.publish(teacherProfileId(principal), requestId);
	}

	@GetMapping("/requests/{requestId}/printable")
	public Printable printable(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID requestId) {
		return studio.printable(teacherProfileId(principal), requestId);
	}

	public record StudioRequest(
		@NotNull UUID studentId,
		@NotNull UUID diagnosisId,
		@NotEmpty @Size(max = 20) List<@Valid Target> targets,
		@NotNull ProblemDifficulty difficulty
	) {
		CreateProblemStudioCommand toCommand(String idempotencyKey) {
			return new CreateProblemStudioCommand(studentId, diagnosisId,
				targets.stream().map(Target::toCommand).toList(), difficulty, idempotencyKey);
		}
	}

	public record Target(
		@NotBlank @Size(max = 80) String areaTag,
		@NotNull ProblemTypeTag typeTag,
		@Min(1) @Max(20) int count,
		@NotBlank @Size(max = 120) String skillNodeId,
		@Valid Passage passage,
		@Valid WorkSelection workSelection
	) {
		CreateProblemStudioCommand.Target toCommand() {
			return new CreateProblemStudioCommand.Target(areaTag, typeTag, count, skillNodeId,
				passage == null ? null : passage.toCommand(),
				workSelection == null ? null : workSelection.toCommand());
		}
	}

	@PostMapping("/requests/{requestId}/executions/{executionId}/slots/{slotIndex}/revisions")
	public ResponseEntity<RevisionView> revise(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID requestId,
		@PathVariable UUID executionId,
		@PathVariable int slotIndex,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
		@Valid @RequestBody RevisionRequest request
	) {
		RevisionView result=revisions.refine(teacherProfileId(principal),requestId,executionId,slotIndex,
			request.baseRevisionNo(),request.revisionKind(),request.instruction(),idempotencyKey);
		return ResponseEntity.accepted().body(result);
	}

	public record Passage(
		@Size(max = 40) String areaTag,
		@Size(max = 40) String domain,
		@Size(max = 300) String topicHint,
		@Min(1) @Max(5000) Integer wordCount,
		@Size(max = 20) String sentenceComplexity,
		@Min(2) @Max(6) Integer paragraphCount,
		@Size(max = 40) String sourceKind,
		@Size(max = 40) String bannedTopicsVersion
	) {
		CreateProblemStudioCommand.Passage toCommand() {
			return new CreateProblemStudioCommand.Passage(areaTag, domain, topicHint, wordCount,
				sentenceComplexity, paragraphCount, sourceKind, bannedTopicsVersion);
		}
	}

	public record WorkSelection(
		@Size(max = 40) String genre,
		@Size(max = 100) String era,
		@Size(max = 20) List<@NotBlank @Size(max = 100) String> conceptKeywords
	) {
		CreateProblemStudioCommand.WorkSelection toCommand() {
			return new CreateProblemStudioCommand.WorkSelection(genre, era, conceptKeywords);
		}
	}

	public record SelectionRequest(@NotNull @Size(max = 20) List<@NotNull UUID> itemIds) { }
	public record RevisionRequest(@Min(0) int baseRevisionNo,@NotBlank String revisionKind,
		@NotBlank @Size(max=2000) String instruction) { }
	public record StudioRequestResponse(UUID requestId, String status, boolean replayed) { }

	private static UUID teacherProfileId(AuthenticatedAccount principal) {
		return principal == null ? null : principal.teacherProfileId();
	}
}
