package com.checkon.detection.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.detection.application.DetectionExecutionException;
import com.checkon.detection.application.NoLearningRecordsException;
import com.checkon.detection.application.PrepareDetectionRunException;
import com.checkon.detection.integration.ai.RiskDetectionClientException;

@RestControllerAdvice(assignableTypes = DetectionRunController.class)
public class DetectionRunExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> invalidRequest() {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.");
	}

	@ExceptionHandler(NoLearningRecordsException.class)
	ResponseEntity<ErrorResponse> noLearningRecords() {
		return error(
			HttpStatus.UNPROCESSABLE_ENTITY,
			"NO_LEARNING_RECORDS",
			"분석 기간에 학습 기록이 없습니다."
		);
	}

	@ExceptionHandler(PrepareDetectionRunException.class)
	ResponseEntity<ErrorResponse> idempotencyConflict() {
		return error(
			HttpStatus.CONFLICT,
			"IDEMPOTENCY_CONFLICT",
			"같은 분석일에 다른 학습 snapshot이 이미 준비되었습니다."
		);
	}

	@ExceptionHandler(IllegalStateException.class)
	ResponseEntity<ErrorResponse> stateConflict() {
		return error(
			HttpStatus.CONFLICT,
			"DETECTION_RUN_STATE_CONFLICT",
			"현재 Detection 실행 상태에서는 요청을 처리할 수 없습니다."
		);
	}

	@ExceptionHandler(DetectionExecutionException.class)
	ResponseEntity<ErrorResponse> executionFailure(
		DetectionExecutionException exception
	) {
		if (exception.reason() == DetectionExecutionException.Reason.RUN_NOT_FOUND) {
			return error(
				HttpStatus.NOT_FOUND,
				"RUN_NOT_FOUND",
				"Detection 실행을 찾을 수 없습니다."
			);
		}
		return error(
			HttpStatus.INTERNAL_SERVER_ERROR,
			exception.reason().name(),
			"Detection 결과를 처리하지 못했습니다."
		);
	}

	@ExceptionHandler(RiskDetectionClientException.class)
	ResponseEntity<ErrorResponse> aiFailure(RiskDetectionClientException exception) {
		HttpStatus status = exception.reason()
			== RiskDetectionClientException.Reason.IDEMPOTENCY_CONFLICT
			? HttpStatus.CONFLICT
			: HttpStatus.BAD_GATEWAY;
		return error(status, exception.reason().name(), "AI 분석 요청에 실패했습니다.");
	}

	private ResponseEntity<ErrorResponse> error(
		HttpStatus status,
		String code,
		String message
	) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) {
	}
}
