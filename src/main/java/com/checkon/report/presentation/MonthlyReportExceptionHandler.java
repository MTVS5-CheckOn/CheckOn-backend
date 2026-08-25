package com.checkon.report.presentation;
import java.util.Map; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.ExceptionHandler; import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.checkon.report.application.MonthlyReportException;
@RestControllerAdvice(assignableTypes=MonthlyReportController.class) public class MonthlyReportExceptionHandler {
	@ExceptionHandler(MonthlyReportException.class) ResponseEntity<?> handle(MonthlyReportException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
}
