package com.checkon.counsel.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.checkon.counsel.application.CounselException;
import com.checkon.counsel.application.GuardianLabelSuggestionException;

@RestControllerAdvice(assignableTypes = {
	CounselDraftController.class, InquiryClassificationController.class, GuardianLabelController.class
})
public class CounselDraftExceptionHandler {

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class,
		MissingRequestHeaderException.class
	})
	ResponseEntity<ErrorResponse> invalidRequest() {
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.");
	}

	@ExceptionHandler(CounselException.class)
	ResponseEntity<ErrorResponse> failure(CounselException exception) {
		return switch (exception.reason()) {
			case INVALID_PRINCIPAL -> response(
				HttpStatus.UNAUTHORIZED, "INVALID_TEACHER_PRINCIPAL", "유효한 강사 인증 정보가 필요합니다."
			);
			case INVALID_REQUEST -> response(
				HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요."
			);
			case IDEMPOTENCY_CONFLICT -> response(
				HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "같은 멱등 키가 다른 요청에 이미 사용되었습니다."
			);
			case JOB_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND, "COUNSEL_JOB_NOT_FOUND", "상담 초안 잡을 찾을 수 없습니다."
			);
			case TARGET_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND, "COUNSEL_TARGET_NOT_FOUND", "학생 또는 반을 찾을 수 없습니다."
			);
			case UPSTREAM_UNAVAILABLE -> response(
				HttpStatus.BAD_GATEWAY, "COUNSEL_AI_UNAVAILABLE", "상담 AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
			);
			case UPSTREAM_INTERNAL_ERROR -> response(
				HttpStatus.BAD_GATEWAY, "COUNSEL_AI_INTERNAL_ERROR", "상담 AI 서버 내부 오류입니다. 다시 시도해도 같은 결과일 수 있습니다."
			);
			case CLASSIFICATION_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND, "COUNSEL_CLASSIFICATION_NOT_FOUND", "분류 결과를 찾을 수 없습니다."
			);
			case DRAFT_NOT_READY -> response(
				HttpStatus.CONFLICT, "COUNSEL_DRAFT_NOT_READY", "초안이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."
			);
		};
	}

	@ExceptionHandler(GuardianLabelSuggestionException.class)
	ResponseEntity<ErrorResponse> guardianLabelFailure(GuardianLabelSuggestionException exception) {
		return switch (exception.reason()) {
			case INVALID_PRINCIPAL -> response(
				HttpStatus.UNAUTHORIZED, "INVALID_TEACHER_PRINCIPAL", "유효한 강사 인증 정보가 필요합니다."
			);
			case TARGET_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND, "GUARDIAN_NOT_FOUND", "학부모를 찾을 수 없습니다."
			);
			case SUGGESTION_NOT_FOUND -> response(
				HttpStatus.NOT_FOUND, "GUARDIAN_LABEL_SUGGESTION_NOT_FOUND", "라벨 제안을 찾을 수 없습니다."
			);
			case INVALID_DECISION -> response(
				HttpStatus.BAD_REQUEST, "INVALID_GUARDIAN_LABEL_DECISION", "라벨 판단 값을 확인해 주세요."
			);
			case DECISION_CONFLICT -> response(
				HttpStatus.CONFLICT, "GUARDIAN_LABEL_DECISION_CONFLICT", "이미 다른 판단으로 처리된 라벨 제안입니다."
			);
			case UPSTREAM_FAILURE -> response(
				HttpStatus.BAD_GATEWAY, "GUARDIAN_LABEL_AI_UNAVAILABLE", "현재 라벨을 분석할 수 없습니다."
			);
		};
	}

	private static ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

	public record ErrorResponse(String code, String message) {
	}
}
