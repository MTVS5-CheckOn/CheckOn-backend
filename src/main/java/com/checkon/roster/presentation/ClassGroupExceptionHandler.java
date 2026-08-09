package com.checkon.roster.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.checkon.roster.application.ClassManagementException;

@RestControllerAdvice(assignableTypes = ClassGroupController.class)
public class ClassGroupExceptionHandler {
	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ErrorResponse> invalidRequest() {
		return response(
			HttpStatus.BAD_REQUEST,
			"INVALID_REQUEST",
			"요청 값을 확인해 주세요."
		);
	}

	@ExceptionHandler(ClassManagementException.class)
	ResponseEntity<ErrorResponse> failure(ClassManagementException exception) {
		return switch (exception.reason()) {
			case INVALID_PRINCIPAL -> response(
				HttpStatus.UNAUTHORIZED,
				"INVALID_TEACHER_PRINCIPAL",
				"유효한 강사 인증 정보가 필요합니다."
			);
			case NOT_FOUND -> response(
				HttpStatus.NOT_FOUND,
				"CLASS_NOT_FOUND",
				"클래스를 찾을 수 없습니다."
			);
			case INVALID_REQUEST -> response(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"요청 값을 확인해 주세요."
			);
			case INVALID_STATE -> response(
				HttpStatus.CONFLICT,
				"INVALID_CLASS_STATE",
				"현재 클래스 상태에서는 요청을 처리할 수 없습니다."
			);
		};
	}

	private ResponseEntity<ErrorResponse> response(
		HttpStatus status,
		String code,
		String message
	) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) {
	}
}
