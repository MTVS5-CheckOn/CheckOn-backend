package com.checkon.account.presentation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.account.application.DuplicateEmailException;

/**
 * 강사 가입 과정의 업무·입력 예외를 공개 API 오류 계약으로 변환한다.
 *
 * <p>예외 메시지에 이메일이나 비밀번호 같은 입력값을 포함하지 않는다.</p>
 */
@RestControllerAdvice(assignableTypes = TeacherSignUpController.class)
public class TeacherSignUpExceptionHandler {

	@ExceptionHandler(DuplicateEmailException.class)
	ResponseEntity<ErrorResponse> handleDuplicateEmail() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
			"EMAIL_ALREADY_EXISTS",
			"이미 가입된 이메일입니다.",
			List.of()
		));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleValidation(
		MethodArgumentNotValidException exception
	) {
		List<FieldViolation> violations = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(TeacherSignUpExceptionHandler::toViolation)
			.toList();
		return ResponseEntity.badRequest().body(new ErrorResponse(
			"INVALID_REQUEST",
			"요청 값을 확인해 주세요.",
			violations
		));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ErrorResponse> handleInvalidArgument() {
		return ResponseEntity.badRequest().body(new ErrorResponse(
			"INVALID_REQUEST",
			"요청 값을 확인해 주세요.",
			List.of()
		));
	}

	private static FieldViolation toViolation(FieldError error) {
		return new FieldViolation(error.getField(), error.getDefaultMessage());
	}

	public record ErrorResponse(
		String code,
		String message,
		List<FieldViolation> fieldErrors
	) {
	}

	public record FieldViolation(String field, String message) {
	}
}
