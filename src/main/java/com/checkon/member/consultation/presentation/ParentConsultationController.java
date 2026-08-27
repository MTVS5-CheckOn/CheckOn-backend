package com.checkon.member.consultation.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.presentation.CursorPage;
import com.checkon.member.common.presentation.IdempotencyKeys;
import com.checkon.member.common.presentation.IdempotentResponses;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.consultation.application.ParentConsultationService;
import com.checkon.member.consultation.application.dto.ConsultationDetailResponse;
import com.checkon.member.consultation.application.dto.ConsultationResponse;
import com.checkon.member.consultation.application.dto.CreateConsultationRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/member/parents/me")
public class ParentConsultationController {

	private final ParentConsultationService service;
	private final ObjectMapper objectMapper;

	public ParentConsultationController(
		ParentConsultationService service, ObjectMapper objectMapper
	) {
		this.service = service;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/consultations")
	public ResponseEntity<String> create(
		@CurrentMember MemberSubject subject,
		@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
		@RequestBody String rawBody
	) {
		String key = IdempotencyKeys.require(idempotencyKey);
		CreateConsultationRequest request = parse(rawBody);
		IdempotentOutcome outcome = service.create(subject, key, rawBody, request);
		return IdempotentResponses.of(outcome);
	}

	@GetMapping("/children/{studentId}/consultations")
	public MemberResponse<CursorPage<ConsultationResponse>> list(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", required = false) Integer limit
	) {
		return MemberResponse.of(service.list(subject, studentId, cursor, limit));
	}

	@GetMapping("/children/{studentId}/consultations/{consultationId}")
	public MemberResponse<ConsultationDetailResponse> get(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@PathVariable UUID consultationId
	) {
		return MemberResponse.of(service.get(subject, studentId, consultationId));
	}

	// TODO(MB-09): 취소 가능 시점이 확정되기 전에는 cancellation endpoint 를 열지 않는다.

	private CreateConsultationRequest parse(String rawBody) {
		if (rawBody == null || rawBody.isBlank()) {
			throw invalidBody("request body is required");
		}
		try {
			return objectMapper.readValue(rawBody, CreateConsultationRequest.class);
		}
		catch (JacksonException exception) {
			throw invalidBody("request body is not readable");
		}
	}

	private static MemberException invalidBody(String message) {
		return new MemberException(MemberErrorCode.INVALID_REQUEST, message,
			List.of(new FieldViolation("body", message)));
	}
}
