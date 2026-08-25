package com.checkon.member.common.error;

import org.springframework.http.HttpStatus;

/**
 * member 경계가 내려보내는 오류 코드 전량.
 *
 * <p>정본은 {@code docs/MEMBER_ERROR_CODES.md} 이고 계약
 * {@code src/main/resources/openapi/member-api.yaml} 의 {@code MemberErrorCode} enum 과
 * 항상 같은 집합이어야 한다. MemberCodeRuleTest 의 G13 이 이를 강제한다.</p>
 *
 * <p>사용자 문구는 프론트가 code 로 분기하므로 여기에 message 를 두지 않는다.
 * 백엔드 message 는 개발자용 설명이며 {@link MemberException} 을 만들 때 넘긴다.</p>
 */
public enum MemberErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST),
	AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
	ACCOUNT_NOT_ACTIVE(HttpStatus.UNAUTHORIZED),
	ROLE_FORBIDDEN(HttpStatus.FORBIDDEN),
	STUDENT_ACTIVATION_REQUIRED(HttpStatus.FORBIDDEN),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
	REVISION_CONFLICT(HttpStatus.CONFLICT),
	CHILD_ALREADY_LINKED(HttpStatus.CONFLICT),
	ATTEMPT_ALREADY_SUBMITTED(HttpStatus.CONFLICT),
	INVITE_ALREADY_CLAIMED(HttpStatus.CONFLICT),
	INVITE_EXPIRED(HttpStatus.GONE),
	SUBMISSION_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT),
	RELATIONSHIP_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
	WORKSHEET_NOT_GRADABLE(HttpStatus.UNPROCESSABLE_CONTENT),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
	INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR),
	DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
	DEPENDENCY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT);

	private final HttpStatus status;

	MemberErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
