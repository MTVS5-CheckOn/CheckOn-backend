package com.checkon.account.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.account.application.AccountNotActiveException;
import com.checkon.account.application.InvalidCredentialsException;
import com.checkon.account.application.InvalidSessionException;

/**
 * 내부 인증 실패를 개인정보나 토큰을 포함하지 않는 안정된 HTTP 오류 계약으로 바꾼다.
 */
@RestControllerAdvice(assignableTypes = AuthenticationController.class)
public class AuthenticationExceptionHandler {

	@ExceptionHandler(InvalidCredentialsException.class)
	ResponseEntity<ErrorResponse> invalidCredentials() {
		return unauthorized(
			"INVALID_CREDENTIALS",
			"이메일 또는 비밀번호를 확인해 주세요."
		);
	}

	@ExceptionHandler(AccountNotActiveException.class)
	ResponseEntity<ErrorResponse> accountNotActive() {
		return unauthorized(
			"ACCOUNT_NOT_ACTIVE",
			"사용할 수 없는 계정입니다."
		);
	}

	@ExceptionHandler(InvalidSessionException.class)
	ResponseEntity<ErrorResponse> invalidSession() {
		return unauthorized(
			"SESSION_INVALID",
			"인증 세션이 유효하지 않습니다."
		);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> invalidRequest() {
		return ResponseEntity.badRequest().body(
			new ErrorResponse("INVALID_REQUEST", "요청 값을 확인해 주세요.")
		);
	}

	private static ResponseEntity<ErrorResponse> unauthorized(
		String code,
		String message
	) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) {
	}
}
