package com.checkon.member.membership.presentation;

import org.springframework.http.ResponseEntity;
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
import com.checkon.member.membership.application.InvitationClaimCommand;
import com.checkon.member.membership.application.InvitationClaimService;
import com.checkon.member.membership.application.InvitationVerificationService;
import com.checkon.member.membership.application.InviteVerificationView;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 학부모 의 강사 초대 검증·등록.
 *
 * <p>🔴 역할은 컨트롤러 <b>파일명</b>으로 가른다 — 폴더를 역할로 쪼개지 않는다(설계 §3-1).
 * application 서비스는 {@code MemberSubject} 만 받고 누가 불렀는지 모른다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/parents/me/invitations")
public class ParentInvitationController {

	private static final String VERIFICATION_ROUTE_KEY =
		"POST /member/parents/me/invitations/verification";

	private final InvitationVerificationService verificationService;
	private final InvitationClaimService claimService;
	private final MemberRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;

	public ParentInvitationController(
		InvitationVerificationService verificationService,
		InvitationClaimService claimService,
		MemberRateLimiter rateLimiter,
		ObjectMapper objectMapper
	) {
		this.verificationService = verificationService;
		this.claimService = claimService;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/verification")
	public MemberResponse<InviteVerificationView> verifyParentInvitation(
		@CurrentMember MemberSubject subject,
		@RequestBody String rawBody,
		HttpServletRequest request
	) {
		// 🔴 해시 조회보다 먼저 센다. 코드 공간을 훑는 것을 늦추는 것이 목적이다.
		rateLimiter.check(VERIFICATION_ROUTE_KEY, subject.accountId(), request.getRemoteAddr());
		InvitationCodeRequest body = MembershipRequestBodies.parse(
			objectMapper, rawBody, InvitationCodeRequest.class);
		return MemberResponse.of(verificationService.verify(subject,
			MembershipRequestBodies.requireText(body.code(), "code")));
	}

	@PostMapping
	public ResponseEntity<String> claimParentInvitation(
		@CurrentMember MemberSubject subject,
		@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
		@RequestBody String rawBody,
		HttpServletRequest request
	) {
		rateLimiter.check(InvitationClaimService.PARENT_ROUTE_KEY, subject.accountId(),
			request.getRemoteAddr());
		String key = IdempotencyKeys.require(idempotencyKey);
		InvitationCodeRequest body = MembershipRequestBodies.parse(
			objectMapper, rawBody, InvitationCodeRequest.class);
		InvitationClaimCommand command = new InvitationClaimCommand(
			MembershipRequestBodies.requireText(body.code(), "code"), key, rawBody);
		return IdempotentResponses.of(claimService.claim(subject, command));
	}
}
