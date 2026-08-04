package com.checkon.dashboard.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.checkon.dashboard.application.FutureBriefingDateException;

@RestControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {
	@ExceptionHandler(FutureBriefingDateException.class)
	ResponseEntity<ErrorResponse> futureDate(FutureBriefingDateException exception) {
		return ResponseEntity.badRequest().body(new ErrorResponse(
			"FUTURE_DATE_NOT_ALLOWED", exception.getMessage()
		));
	}

	@ExceptionHandler({
		MissingServletRequestParameterException.class,
		MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ErrorResponse> invalidDate() {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
			new ErrorResponse("INVALID_REQUEST", "요청 값을 확인해 주세요.")
		);
	}

	public record ErrorResponse(String code, String message) {
	}
}
