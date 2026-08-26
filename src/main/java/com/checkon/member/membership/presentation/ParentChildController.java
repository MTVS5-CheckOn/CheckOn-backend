package com.checkon.member.membership.presentation;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.presentation.IdempotencyKeys;
import com.checkon.member.common.presentation.IdempotentResponses;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.membership.application.ChildQueryService;
import com.checkon.member.membership.application.ChildRegistrationCommand;
import com.checkon.member.membership.application.ChildRegistrationService;
import com.checkon.member.membership.application.ChildVerificationService;
import com.checkon.member.membership.application.ChildVerificationView;
import com.checkon.member.membership.application.ChildView;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 학부모의 자녀 목록·사전 확인·등록.
 *
 * <p>역할 판정은 {@code MemberSecurityConfiguration} 이 {@code /member/parents/**} 경로에
 * 이미 걸어 두었다 — 컨트롤러에서 다시 분기하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/parents/me/children")
public class ParentChildController {

	private static final String VERIFICATION_ROUTE_KEY =
		"POST /member/parents/me/children/verification";

	private final ChildQueryService childQueryService;
	private final ChildVerificationService childVerificationService;
	private final ChildRegistrationService childRegistrationService;
	private final MemberRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;

	public ParentChildController(
		ChildQueryService childQueryService,
		ChildVerificationService childVerificationService,
		ChildRegistrationService childRegistrationService,
		MemberRateLimiter rateLimiter,
		ObjectMapper objectMapper
	) {
		this.childQueryService = childQueryService;
		this.childVerificationService = childVerificationService;
		this.childRegistrationService = childRegistrationService;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	@GetMapping
	public MemberResponse<Map<String, List<ChildView>>> listParentChildren(
		@CurrentMember MemberSubject subject
	) {
		// 🔴 0명이어도 200 + 빈 배열이다. 오류가 아니다(분기표 §0-1 ⑧).
		return MemberResponse.of(Map.of("items", childQueryService.listChildren(subject)));
	}

	@PostMapping("/verification")
	public MemberResponse<ChildVerificationView> verifyParentChild(
		@CurrentMember MemberSubject subject,
		@RequestBody String rawBody,
		HttpServletRequest request
	) {
		// 🔴 공개 ID 조회보다 먼저 센다. 뒤에 두면 열거가 이미 끝난 뒤에 세는 셈이다.
		rateLimiter.check(VERIFICATION_ROUTE_KEY, subject.accountId(), request.getRemoteAddr());
		ChildVerificationRequest body = MembershipRequestBodies.parse(
			objectMapper, rawBody, ChildVerificationRequest.class);
		return MemberResponse.of(childVerificationService.verify(subject,
			MembershipRequestBodies.requireText(body.studentPublicId(), "studentPublicId")));
	}

	@PostMapping
	public ResponseEntity<String> registerParentChild(
		@CurrentMember MemberSubject subject,
		@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
		@RequestBody String rawBody,
		HttpServletRequest request
	) {
		rateLimiter.check(ChildRegistrationService.ROUTE_KEY, subject.accountId(),
			request.getRemoteAddr());
		String key = IdempotencyKeys.require(idempotencyKey);
		ChildRegistrationRequest body = MembershipRequestBodies.parse(
			objectMapper, rawBody, ChildRegistrationRequest.class);
		ChildRegistrationCommand command = new ChildRegistrationCommand(
			MembershipRequestBodies.requireText(body.studentPublicId(), "studentPublicId"),
			key, rawBody);
		return IdempotentResponses.of(childRegistrationService.register(subject, command));
	}
}
