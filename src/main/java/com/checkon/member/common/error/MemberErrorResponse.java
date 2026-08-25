package com.checkon.member.common.error;

/**
 * member 오류 봉투. 계약 §7-1 의 {@code {"error":{"code","message","details"?}}} 형태다.
 *
 * <p>승우님 컨트롤러들은 top-level {@code {code, message}} 를 쓴다. 그 형식을 바꾸지 않으려고
 * {@link MemberExceptionHandler} 를 member 패키지로만 범위 제한한다.</p>
 */
public record MemberErrorResponse(Body error) {

	public static MemberErrorResponse of(MemberErrorCode code, String message, Object details) {
		return new MemberErrorResponse(new Body(code.name(), message, details));
	}

	public record Body(String code, String message, Object details) {
	}
}
