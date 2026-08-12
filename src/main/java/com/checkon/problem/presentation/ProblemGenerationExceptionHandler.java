package com.checkon.problem.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.problem.application.ProblemGenerationException;

@RestControllerAdvice(assignableTypes = ProblemGenerationController.class)
public class ProblemGenerationExceptionHandler {

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class,
		MissingRequestHeaderException.class
	})
	ResponseEntity<ErrorResponse> invalidRequest() {
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.");
	}

	@ExceptionHandler(ProblemGenerationException.class)
	ResponseEntity<ErrorResponse> failure(ProblemGenerationException exception) {
		return switch (exception.reason()) {
			case INVALID_PRINCIPAL -> response(
				HttpStatus.UNAUTHORIZED,
				"INVALID_TEACHER_PRINCIPAL",
				"유효한 강사 인증 정보가 필요합니다."
			);
			case TARGET_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND,
				"PROBLEM_TARGET_NOT_FOUND",
				"출제 대상을 찾을 수 없습니다."
			);
			case INVALID_REQUEST -> response(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"요청 값을 확인해 주세요."
			);
			case REQUEST_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND,
				"PROBLEM_REQUEST_NOT_FOUND",
				"출제 요청을 찾을 수 없습니다."
			);
			case IDEMPOTENCY_CONFLICT -> response(
				HttpStatus.CONFLICT,
				"IDEMPOTENCY_CONFLICT",
				"같은 멱등 키가 다른 요청에 이미 사용되었습니다."
			);
		};
	}

	private static ResponseEntity<ErrorResponse> response(
		HttpStatus status,
		String code,
		String message
	) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) { }
}
