package com.checkon.detection.presentation;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.detection.application.PrepareDetectionRunException;
import com.checkon.detection.application.DetectionExecutionException;
import com.checkon.detection.integration.ai.RiskDetectionClientException;

@Profile("dev")
@RestControllerAdvice(assignableTypes = DevDetectionRunController.class)
public class DevDetectionExceptionHandler {

	@ExceptionHandler(PrepareDetectionRunException.class)
	ResponseEntity<Map<String, String>> handleConflict(
		PrepareDetectionRunException exception
	) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(Map.of(
				"code", "DETECTION_RUN_CONFLICT",
				"message", exception.getMessage()
			));
	}

	@ExceptionHandler(DetectionExecutionException.class)
	ResponseEntity<Map<String, String>> handleExecution(
		DetectionExecutionException exception
	) {
		HttpStatus status = exception.reason()
			== DetectionExecutionException.Reason.RUN_NOT_FOUND
			? HttpStatus.NOT_FOUND
			: HttpStatus.INTERNAL_SERVER_ERROR;
		return error(status, exception.reason().name(), exception.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	ResponseEntity<Map<String, String>> handleStateConflict(
		IllegalStateException exception
	) {
		return error(
			HttpStatus.CONFLICT,
			"DETECTION_RUN_STATE_CONFLICT",
			exception.getMessage()
		);
	}

	@ExceptionHandler(RiskDetectionClientException.class)
	ResponseEntity<Map<String, String>> handleAiFailure(
		RiskDetectionClientException exception
	) {
		HttpStatus status = exception.reason()
			== RiskDetectionClientException.Reason.IDEMPOTENCY_CONFLICT
			? HttpStatus.CONFLICT
			: HttpStatus.BAD_GATEWAY;
		return error(status, exception.reason().name(), exception.getMessage());
	}

	private ResponseEntity<Map<String, String>> error(
		HttpStatus status,
		String code,
		String message
	) {
		return ResponseEntity.status(status)
			.body(Map.of("code", code, "message", message));
	}
}
