package com.checkon.counsel.presentation;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.application.CounselDraftRequestService;
import com.checkon.counsel.application.CreateCounselDraftCommand;
import com.checkon.counsel.application.CreateCounselInquiryCommand;
import com.checkon.counsel.domain.CounselBlockedReason;
import com.checkon.counsel.domain.CounselDraftStatus;
import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.integration.ai.dto.CounselCitation;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Frontend-facing surface for the counsel draft flow
 * (apidog_counsel_3endpoints_20260819.md). Mirrors the AI contract's three
 * calls one-to-one — create, fetch, refine — but takes real roster ids and
 * raw text; {@link CounselDraftRequestService} resolves aliases and masks
 * text before any of it reaches the AI server.
 */
@RestController
@RequestMapping("/api/v1/counsel/drafts")
public class CounselDraftController {

	private final CounselDraftRequestService service;

	public CounselDraftController(CounselDraftRequestService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<CreateDraftResponse> create(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
		@Valid @RequestBody CreateDraftRequest request
	) {
		var result = service.createDraft(teacherProfileId(principal), request.toCommand(idempotencyKey));
		return ResponseEntity.accepted()
			.location(URI.create("/api/v1/counsel/drafts/" + result.jobId()))
			.body(new CreateDraftResponse(result.jobId(), result.status()));
	}

	@GetMapping("/{jobId}")
	public GetDraftResponse get(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable String jobId
	) {
		return toResponse(service.getDraft(teacherProfileId(principal), jobId));
	}

	@PostMapping("/{jobId}/refine")
	public RefineDraftResponse refine(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable String jobId,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
		@Valid @RequestBody RefineDraftRequest request
	) {
		var response = service.refine(teacherProfileId(principal), jobId, idempotencyKey, request.instruction(), request.turnNo());
		return toResponse(response);
	}

	/**
	 * Records the text the teacher actually sent through their own channel
	 * (contract appendix §7) — purely local bookkeeping, no AI call.
	 */
	@PostMapping("/{jobId}/sent")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markSent(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable String jobId,
		@Valid @RequestBody MarkSentRequest request
	) {
		service.markSent(teacherProfileId(principal), jobId, request.text());
	}

	private static GetDraftResponse toResponse(CounselDraftGetResponse response) {
		var result = response.data().result();
		return new GetDraftResponse(
			response.data().jobId(),
			response.data().status(),
			result == null ? null : new GetDraftResponse.DraftResult(
				result.draftStatus(), result.text(), toCitationViews(result.citations()),
				result.labelsApplied(), result.statusReason(), result.generatedAt()
			)
		);
	}

	private static RefineDraftResponse toResponse(CounselDraftRefineResponse response) {
		return new RefineDraftResponse(
			response.data().applied(), response.data().text(),
			toCitationViews(response.data().citations()), response.data().blockedReason()
		);
	}

	private static List<CitationView> toCitationViews(List<CounselCitation> citations) {
		return citations == null ? List.of()
			: citations.stream().map(c -> new CitationView(c.citeId(), c.recordId(), c.summary())).toList();
	}

	private static UUID teacherProfileId(AuthenticatedAccount principal) {
		return principal == null ? null : principal.teacherProfileId();
	}

	public record CitationView(String citeId, String recordId, String summary) {
	}

	public record CreateDraftRequest(
		@NotNull UUID studentId,
		@NotNull UUID classId,
		@NotBlank @Size(max = 200) String inquiryRef,
		@NotNull CounselTopic topic,
		@NotNull CounselUrgency urgency,
		@NotNull OffsetDateTime receivedAt,
		@NotBlank String text,
		List<@NotBlank String> labels,
		List<DismissedSuggestionRequest> dismissedSuggestions,
		@NotBlank @Size(max = 120) String periodLabel,
		@NotNull List<FactRequest> facts
	) {
		CreateCounselInquiryCommand toCommand(String idempotencyKey) {
			return new CreateCounselInquiryCommand(
				studentId, classId, idempotencyKey, inquiryRef, topic, urgency, receivedAt, text,
				labels == null ? List.of() : List.copyOf(labels),
				dismissedSuggestions == null ? List.of() : dismissedSuggestions.stream()
					.map(value -> new CreateCounselDraftCommand.DismissedSuggestion(value.axis(), value.value()))
					.toList(),
				periodLabel,
				facts == null ? List.of() : facts.stream()
					.map(value -> new CreateCounselDraftCommand.Fact(value.recordId(), value.summary()))
					.toList()
			);
		}

		public record DismissedSuggestionRequest(@NotBlank String axis, @NotBlank String value) {
		}

		public record FactRequest(String recordId, @NotBlank String summary) {
		}
	}

	public record CreateDraftResponse(String jobId, CounselJobPhase status) {
	}

	public record GetDraftResponse(String jobId, CounselJobPhase status, DraftResult result) {
		public record DraftResult(
			CounselDraftStatus draftStatus,
			String text,
			List<CitationView> citations,
			List<String> labelsApplied,
			String statusReason,
			OffsetDateTime generatedAt
		) {
		}
	}

	public record RefineDraftRequest(@NotBlank String instruction, Integer turnNo) {
	}

	public record MarkSentRequest(@NotBlank String text) {
	}

	public record RefineDraftResponse(
		boolean applied,
		String text,
		List<CitationView> citations,
		CounselBlockedReason blockedReason
	) {
	}
}
