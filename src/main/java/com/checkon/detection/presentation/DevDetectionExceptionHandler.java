package com.checkon.detection.presentation;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.detection.application.PrepareDetectionRunException;

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
}
