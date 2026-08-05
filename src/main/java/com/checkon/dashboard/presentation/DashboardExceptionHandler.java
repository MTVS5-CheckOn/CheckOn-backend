package com.checkon.dashboard.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.checkon.dashboard.application.FutureBriefingDateException;
import com.checkon.dashboard.application.InvalidDashboardCalendarRangeException;

@RestControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {
	@ExceptionHandler(FutureBriefingDateException.class)
	ResponseEntity<ErrorResponse> futureDate(FutureBriefingDateException exception) {
		return ResponseEntity.badRequest().body(new ErrorResponse(
			"FUTURE_DATE_NOT_ALLOWED", exception.getMessage()
		));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ErrorResponse> missingParameter() {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
			new ErrorResponse("MISSING_REQUEST_PARAMETER", "필수 요청 파라미터를 확인해 주세요.")
		);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> invalidDateFormat() {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
			new ErrorResponse("INVALID_DATE_FORMAT", "날짜는 yyyy-MM-dd 형식이어야 합니다.")
		);
	}

	@ExceptionHandler(InvalidDashboardCalendarRangeException.class)
	ResponseEntity<ErrorResponse> invalidCalendarRange(
		InvalidDashboardCalendarRangeException exception
	) {
		return ResponseEntity.badRequest().body(new ErrorResponse(
			"INVALID_CALENDAR_RANGE", exception.getMessage()
		));
	}

	public record ErrorResponse(String code, String message) {
	}
}
