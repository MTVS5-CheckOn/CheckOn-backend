package com.checkon.publication.presentation;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.checkon.publication.domain.PublicationErrorCode;
import com.checkon.publication.domain.PublicationException;

/**
 * publication 컨트롤러 전용 오류 변환.
 *
 * <p>🔴 <b>범위를 이 컨트롤러로 제한한다.</b> member 의 advice 는
 * {@code basePackageClasses = MemberPackageMarker.class} 로, 승우님 것은
 * {@code assignableTypes = MonthlyReportController.class} 로 각자 제한돼 있다 —
 * 전역 advice 를 하나 더 두면 남의 컨트롤러 응답 모양이 조용히 바뀐다.</p>
 *
 * <p>🔴 <b>봉투는 top-level {@code {code, message}}</b> 다. 이 경로의 401·403 은 Spring
 * Security 단계에서 {@code SecurityErrorResponseWriter} 가 그 모양으로 쓴다 — 우리만 다르면
 * <b>한 엔드포인트가 실패 종류에 따라 두 가지 봉투</b>를 낸다.</p>
 *
 * <p>🔴 표준 MVC 예외 넷을 함께 잡는다. 안 잡으면 잘못된 본문·타입이 <b>500</b> 으로 나간다 —
 * member 가 G18 게이트로 막은 것과 같은 구멍이다.</p>
 */
@RestControllerAdvice(assignableTypes = TeacherConsultationController.class)
public class PublicationExceptionHandler {

	@ExceptionHandler(PublicationException.class)
	ResponseEntity<Map<String, String>> handle(PublicationException exception) {
		return body(exception.errorCode(), exception.getMessage());
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		MissingServletRequestParameterException.class,
		MethodArgumentTypeMismatchException.class,
		HttpMessageNotReadableException.class
	})
	ResponseEntity<Map<String, String>> handleBadRequest(Exception exception) {
		// 🔴 message 에 예외 내용·스택을 담지 않는다. 무엇이 잘못됐는지만 고정 문구로 낸다.
		return body(PublicationErrorCode.INVALID_REQUEST, "request is not readable");
	}

	private static ResponseEntity<Map<String, String>> body(
		PublicationErrorCode code, String message
	) {
		return ResponseEntity.status(code.status())
			.body(Map.of("code", code.name(), "message", message));
	}
}
