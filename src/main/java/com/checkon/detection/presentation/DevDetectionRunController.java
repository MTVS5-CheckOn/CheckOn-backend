package com.checkon.detection.presentation;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.detection.application.PrepareDetectionRunService;
import com.checkon.detection.application.PrepareDetectionRunService.PreparedDetectionRun;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;

@Profile("dev")
@RestController
@RequestMapping("/api/dev/detection-runs")
public class DevDetectionRunController {

	private final PrepareDetectionRunService prepareDetectionRunService;

	public DevDetectionRunController(
		PrepareDetectionRunService prepareDetectionRunService
	) {
		this.prepareDetectionRunService = prepareDetectionRunService;
	}

	@PostMapping
	public ResponseEntity<PreparedDetectionRun> prepare(
		@RequestHeader("X-Teacher-Id") UUID teacherId,
		@RequestHeader("X-Tenant-Id") String tenantAlias,
		@RequestParam LocalDate analysisDate,
		@RequestBody AiDetectionRequest request
	) {
		PreparedDetectionRun result = prepareDetectionRunService.prepare(
			teacherId,
			tenantAlias,
			analysisDate,
			request
		);
		if (!result.created()) {
			return ResponseEntity.ok(result);
		}
		return ResponseEntity.created(
			URI.create("/api/dev/detection-runs/" + result.runId())
		).body(result);
	}
}
