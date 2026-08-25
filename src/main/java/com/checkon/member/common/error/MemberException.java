package com.checkon.member.common.error;

/**
 * member 경계의 도메인 오류. HTTP 상태는 {@link MemberErrorCode} 가 정한다.
 *
 * <p>{@code details} 는 code 마다 구조가 다르다(검증 위반 목록, itemId 목록 등).
 * 담을 것이 없으면 {@code null} 이며, 0 이나 빈 문자열로 채우지 않는다.</p>
 */
public class MemberException extends RuntimeException {

	private final transient MemberErrorCode errorCode;
	private final transient Object details;

	public MemberException(MemberErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public MemberException(MemberErrorCode errorCode, String message, Object details) {
		super(message);
		this.errorCode = errorCode;
		this.details = details;
	}

	public MemberErrorCode errorCode() {
		return errorCode;
	}

	public Object details() {
		return details;
	}
}
