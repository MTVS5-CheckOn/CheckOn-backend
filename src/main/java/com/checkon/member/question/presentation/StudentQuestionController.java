package com.checkon.member.question.presentation;

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
import com.checkon.member.question.application.StudentQuestionService;
import com.checkon.member.question.application.dto.CreateQuestionMessageRequest;
import com.checkon.member.question.application.dto.CreateQuestionRequest;
import com.checkon.member.question.application.dto.StudentQuestionDetailResponse;
import com.checkon.member.question.application.dto.StudentQuestionResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 학생 질문 API — 계약 operationId 4개:
 * <ul>
 *   <li>{@code listStudentQuestions} — {@code GET /questions}</li>
 *   <li>{@code createStudentQuestion} — {@code POST /questions}</li>
 *   <li>{@code getStudentQuestion} — {@code GET /questions/{id}}</li>
 *   <li>{@code addStudentQuestionMessage} — {@code POST /questions/{id}/messages}</li>
 * </ul>
 *
 * <p>🔴 대기 학생은 {@link com.checkon.member.common.security.StudentActivationGuard} 가
 * 이 경로를 403 으로 막는다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/students/me/questions")
public class StudentQuestionController {

	private final StudentQuestionService service;
	private final ObjectMapper objectMapper;

	public StudentQuestionController(StudentQuestionService service, ObjectMapper objectMapper) {
		this.service = service;
		this.objectMapper = objectMapper;
	}

	@GetMapping
	public MemberResponse<CursorPage<StudentQuestionResponse>> listStudentQuestions(
		@CurrentMember MemberSubject subject,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", required = false) Integer limit
	) {
		return MemberResponse.of(service.list(subject, cursor, limit));
	}

	@PostMapping
	public ResponseEntity<String> createStudentQuestion(
		@CurrentMember MemberSubject subject,
		@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
		@RequestBody String rawBody
	) {
		String key = IdempotencyKeys.require(idempotencyKey);
		CreateQuestionRequest request = parse(rawBody, CreateQuestionRequest.class);
		IdempotentOutcome outcome = service.create(subject, key, rawBody, request);
		return IdempotentResponses.of(outcome);
	}

	@GetMapping("/{questionId}")
	public MemberResponse<StudentQuestionDetailResponse> getStudentQuestion(
		@CurrentMember MemberSubject subject,
		@PathVariable("questionId") UUID questionId
	) {
		return MemberResponse.of(service.get(subject, questionId));
	}

	@PostMapping("/{questionId}/messages")
	public ResponseEntity<String> addStudentQuestionMessage(
		@CurrentMember MemberSubject subject,
		@PathVariable("questionId") UUID questionId,
		@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
		@RequestBody String rawBody
	) {
		String key = IdempotencyKeys.require(idempotencyKey);
		CreateQuestionMessageRequest request = parse(rawBody, CreateQuestionMessageRequest.class);
		IdempotentOutcome outcome = service.addMessage(
			subject, questionId, key, rawBody, request);
		return IdempotentResponses.of(outcome);
	}

	private <T> T parse(String rawBody, Class<T> type) {
		if (rawBody == null || rawBody.isBlank()) {
			throw invalidBody("request body is required");
		}
		try {
			T parsed = objectMapper.readValue(rawBody, type);
			if (parsed == null) {
				throw invalidBody("request body is required");
			}
			return parsed;
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
