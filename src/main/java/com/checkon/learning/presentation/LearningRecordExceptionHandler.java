package com.checkon.learning.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.learning.application.LearningRecordRegistrationException;

@RestControllerAdvice(assignableTypes = LearningRecordController.class)
public class LearningRecordExceptionHandler {
	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ErrorResponse> invalidRequest() {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.");
	}

	@ExceptionHandler(LearningRecordRegistrationException.class)
	ResponseEntity<ErrorResponse> registrationFailure(
		LearningRecordRegistrationException exception
	) {
		return switch (exception.reason()) {
			case INVALID_TEACHER_PRINCIPAL -> error(
				HttpStatus.UNAUTHORIZED,
				"INVALID_TEACHER_PRINCIPAL",
				"유효한 강사 인증 정보가 필요합니다."
			);
			case INACCESSIBLE_STUDENT, INACCESSIBLE_CLASS_GROUP -> error(
				HttpStatus.NOT_FOUND,
				"LEARNING_RECORD_TARGET_NOT_FOUND",
				"등록 대상 학생 또는 반을 찾을 수 없습니다."
			);
			case INVALID_RECORD -> error(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"요청 값을 확인해 주세요."
			);
		};
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) {
	}
}
