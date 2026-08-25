package com.checkon.member.common.error;

import java.util.List;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.checkon.member.MemberPackageMarker;
import com.checkon.member.common.presentation.RateLimitDetails;

import jakarta.validation.ConstraintViolationException;

/**
 * member 경계 전용 예외 처리.
 *
 * <p>🔴 {@code basePackageClasses} 로 범위를 member 로 묶는다. 범위를 두지 않으면 기존 컨트롤러의
 * 로컬 오류 형식({@code {code, message}})까지 삼켜 강사 API 응답이 바뀐다.</p>
 */
@RestControllerAdvice(basePackageClasses = MemberPackageMarker.class)
public class MemberExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(MemberExceptionHandler.class);

	@ExceptionHandler(MemberException.class)
	public ResponseEntity<MemberErrorResponse> handleMember(MemberException exception) {
		MemberErrorCode code = exception.errorCode();
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
		// 🔴 429 는 헤더까지가 계약이다(분기표 §0-2). 본문만 내려보내면 클라이언트가
		//    언제 다시 시도할지 알 수 없어 즉시 재시도 루프를 돈다.
		if (exception.details() instanceof RateLimitDetails details) {
			builder.header(HttpHeaders.RETRY_AFTER, Long.toString(details.retryAfterSeconds()));
		}
		return builder
			.body(MemberErrorResponse.of(code, exception.getMessage(), exception.details()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<MemberErrorResponse> handleBeanValidation(
		MethodArgumentNotValidException exception
	) {
		List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(FieldViolation::field))
			.toList();
		return invalidRequest("request validation failed", violations);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<MemberErrorResponse> handleConstraint(
		ConstraintViolationException exception
	) {
		List<FieldViolation> violations = exception.getConstraintViolations().stream()
			.map(violation -> new FieldViolation(
				violation.getPropertyPath().toString(), violation.getMessage()))
			.sorted(Comparator.comparing(FieldViolation::field))
			.toList();
		return invalidRequest("request validation failed", violations);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<MemberErrorResponse> handleUnreadableBody(
		HttpMessageNotReadableException exception
	) {
		// 파서 메시지에는 페이로드 조각이 섞이므로 그대로 내려보내지 않는다.
		return invalidRequest("request body is not readable", null);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<MemberErrorResponse> handleTypeMismatch(
		MethodArgumentTypeMismatchException exception
	) {
		FieldViolation violation = new FieldViolation(exception.getName(), "type mismatch");
		return invalidRequest("request parameter type mismatch", List.of(violation));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<MemberErrorResponse> handleUnexpected(Exception exception) {
		// 🔴 message 에 예외 내용·스택·쿼리를 담지 않는다. 원인은 서버 로그에만 남긴다.
		log.error("member request failed", exception);
		MemberErrorCode code = MemberErrorCode.INTERNAL;
		return ResponseEntity.status(code.status())
			.body(MemberErrorResponse.of(code, "unexpected server error", null));
	}

	private ResponseEntity<MemberErrorResponse> invalidRequest(String message, Object details) {
		MemberErrorCode code = MemberErrorCode.INVALID_REQUEST;
		return ResponseEntity.status(code.status())
			.body(MemberErrorResponse.of(code, message, details));
	}
}
