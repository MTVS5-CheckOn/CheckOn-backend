package com.checkon.detection.presentation;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.detection.application.OperationalDetectionRunService;
import com.checkon.detection.application.OperationalDetectionRunService.OperationalDetectionRun;
import com.checkon.detection.application.DetectionRunQueryService;
import com.checkon.detection.domain.DetectionRunStatus;

@RestController
@RequestMapping("/api/v1/detection-runs")
public class DetectionRunController {

	private final OperationalDetectionRunService detectionRunService;
	private final DetectionRunQueryService queryService;

	public DetectionRunController(
		OperationalDetectionRunService detectionRunService,
		DetectionRunQueryService queryService
	) {
		this.detectionRunService = detectionRunService;
		this.queryService = queryService;
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
		if (result.status() != DetectionRunStatus.REQUESTED) {
			return ResponseEntity.ok(response);
		}
		return ResponseEntity.accepted().location(
			URI.create("/api/v1/detection-runs/" + result.runId())
		).body(response);
	}

	@GetMapping("/{runId}")
	public DetectionRunStatusResponse find(
		@AuthenticationPrincipal AuthenticatedAccount authenticatedAccount,
		@PathVariable UUID runId
	) {
		var result = queryService.find(authenticatedAccount.teacherProfileId(), runId);
		return new DetectionRunStatusResponse(
			result.runId(), result.status(), result.analysisDate(),
			result.attemptCount(), result.errorCode()
		);
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

	public record DetectionRunStatusResponse(
		UUID runId,
		DetectionRunStatus status,
		LocalDate analysisDate,
		int attemptCount,
		String errorCode
	) {
	}
}
