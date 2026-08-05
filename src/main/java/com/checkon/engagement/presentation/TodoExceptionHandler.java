package com.checkon.engagement.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.engagement.application.TodoException;

@RestControllerAdvice(assignableTypes = TodoController.class)
public class TodoExceptionHandler {
	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ErrorResponse> invalidBody() { return response(HttpStatus.BAD_REQUEST, "INVALID_TODO_UPDATE", "요청 값을 확인해 주세요."); }
	@ExceptionHandler(TodoException.class)
	ResponseEntity<ErrorResponse> failure(TodoException exception) {
		return switch (exception.reason()) {
			case INVALID_PRINCIPAL -> response(HttpStatus.UNAUTHORIZED, "INVALID_TEACHER_PRINCIPAL", "유효한 강사 인증 정보가 필요합니다.");
			case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "Todo를 찾을 수 없습니다.");
			case INVALID_UPDATE -> response(HttpStatus.BAD_REQUEST, "INVALID_TODO_UPDATE", "done=true만 지원합니다.");
			case INVALID_STATE -> response(HttpStatus.CONFLICT, "INVALID_TODO_STATE", "현재 상태에서는 요청을 처리할 수 없습니다.");
		};
	}
	private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}
	public record ErrorResponse(String code, String message) {}
}
