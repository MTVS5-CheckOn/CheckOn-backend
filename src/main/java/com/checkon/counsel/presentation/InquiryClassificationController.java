package com.checkon.counsel.presentation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.application.InquiryClassificationService;
import com.checkon.counsel.domain.ClassifyFallbackReason;
import com.checkon.counsel.domain.ConfirmationAction;
import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.domain.InquirySentiment;
import com.checkon.counsel.integration.ai.dto.ClassifyResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Frontend-facing surface for {@code POST /v1/classify}/{@code /confirmations}
 * (classify/confirmations contract, 2026-08-20). Classify has no roster refs
 * to resolve — only the raw text and a caller-supplied {@code inquiryRef} —
 * so this controller is thinner than {@link CounselDraftController}.
 *
 * <p>A topic correction redrafts the counsel draft automatically, reusing the
 * original inquiry's stored student/class/facts/labels
 * ({@link InquiryClassificationService#confirm}) — the classify/confirmations
 * contract §4-2 requires the backend to call both AI endpoints on a topic
 * correction, not just record it.
 */
@RestController
@RequestMapping("/api/v1/counsel/inquiries")
public class InquiryClassificationController {

	private final InquiryClassificationService service;

	public InquiryClassificationController(InquiryClassificationService service) {
		this.service = service;
	}

	@PostMapping("/{inquiryRef}/classify")
	public ClassifyResultResponse classify(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable String inquiryRef,
		@Valid @RequestBody ClassifyInquiryRequest request
	) {
		ClassifyResponse.Data data = service.classify(teacherProfileId(principal), inquiryRef, request.text());
		return new ClassifyResultResponse(
			data.topic(), data.sentiment(), data.urgency(),
			data.confidence().topic(), data.confidence().sentiment(), data.confidence().urgency(),
			data.classified(), data.fallbackReason()
		);
	}

	/**
	 * 200 with {@code redraftedJobId} set when a topic correction triggered a
	 * redraft; 204 when the confirmation was recorded but nothing needed
	 * redrafting (a plain confirm, a sentiment/urgency-only correction, or a
	 * topic correction for an inquiry that never had a draft).
	 */
	@PostMapping("/{inquiryRef}/confirmation")
	public ResponseEntity<ConfirmationResultResponse> confirm(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable String inquiryRef,
		@Valid @RequestBody ConfirmClassificationRequest request
	) {
		var redraft = service.confirm(
			teacherProfileId(principal), inquiryRef, request.action(),
			request.correctedTopic(), request.correctedSentiment(), request.correctedUrgency()
		);
		return redraft
			.map(result -> ResponseEntity.ok(new ConfirmationResultResponse(result.jobId(), result.status())))
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NO_CONTENT).build());
	}

	private static UUID teacherProfileId(AuthenticatedAccount principal) {
		return principal == null ? null : principal.teacherProfileId();
	}

	public record ClassifyInquiryRequest(@NotBlank String text) {
	}

	public record ClassifyResultResponse(
		CounselTopic topic,
		InquirySentiment sentiment,
		CounselUrgency urgency,
		BigDecimal confidenceTopic,
		BigDecimal confidenceSentiment,
		BigDecimal confidenceUrgency,
		boolean classified,
		ClassifyFallbackReason fallbackReason
	) {
	}

	public record ConfirmClassificationRequest(
		@NotNull ConfirmationAction action,
		CounselTopic correctedTopic,
		InquirySentiment correctedSentiment,
		CounselUrgency correctedUrgency
	) {
	}

	public record ConfirmationResultResponse(String redraftedJobId, CounselJobPhase redraftedStatus) {
	}
}
