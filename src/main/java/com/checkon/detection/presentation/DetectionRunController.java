package com.checkon.detection.presentation;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.detection.application.OperationalDetectionRunService;
import com.checkon.detection.application.OperationalDetectionRunService.OperationalDetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;

@RestController
@RequestMapping("/api/v1/detection-runs")
public class DetectionRunController {

	private final OperationalDetectionRunService detectionRunService;

	public DetectionRunController(
		OperationalDetectionRunService detectionRunService
	) {
		this.detectionRunService = detectionRunService;
	}

	@PostMapping
	public ResponseEntity<DetectionRunResponse> execute(
		@AuthenticationPrincipal AuthenticatedAccount authenticatedAccount,
		@Valid @RequestBody DetectionRunRequest request
	) {
		// accountId와 TeacherProfile ID는 서로 다른 식별자다. JWT와 현재 DB
		// 세션 검증으로 만든 principal의 teacherProfileId만 application 경계에
		// 전달하고, 요청 body나 X-Teacher-Id를 권한 근거로 사용하지 않는다.
		OperationalDetectionRun result = detectionRunService.execute(
			authenticatedAccount.teacherProfileId(),
			request.analysisDate()
		);
		DetectionRunResponse response = new DetectionRunResponse(
			result.runId(),
			result.status(),
			result.analysisDate(),
			result.created(),
			result.attemptNumber()
		);
		if (!result.created()) {
			return ResponseEntity.ok(response);
		}
		return ResponseEntity.created(
			URI.create("/api/v1/detection-runs/" + result.runId())
		).body(response);
	}

	public record DetectionRunRequest(@NotNull LocalDate analysisDate) {
	}

	public record DetectionRunResponse(
		UUID runId,
		DetectionRunStatus status,
		LocalDate analysisDate,
		boolean created,
		int attemptNumber
	) {
	}
}
