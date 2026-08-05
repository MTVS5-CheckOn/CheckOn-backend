package com.checkon.roster.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.roster.application.StudentPersonalInformationException;

@RestControllerAdvice(assignableTypes = StudentPersonalInformationController.class)
public class StudentPersonalInformationExceptionHandler {
	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ErrorResponse> invalidRequest() {
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.");
	}

	@ExceptionHandler(StudentPersonalInformationException.class)
	ResponseEntity<ErrorResponse> failure(StudentPersonalInformationException exception) {
		return switch (exception.reason()) {
			case INVALID_PRINCIPAL -> response(
				HttpStatus.UNAUTHORIZED,
				"INVALID_TEACHER_PRINCIPAL",
				"유효한 강사 인증 정보가 필요합니다."
			);
			case NOT_FOUND -> response(
				HttpStatus.NOT_FOUND,
				"STUDENT_NOT_FOUND",
				"학생을 찾을 수 없습니다."
			);
			case INVALID_NAME -> response(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"요청 값을 확인해 주세요."
			);
		};
	}

	private ResponseEntity<ErrorResponse> response(
		HttpStatus status, String code, String message
	) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) {
	}
}

