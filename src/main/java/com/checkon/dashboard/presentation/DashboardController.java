package com.checkon.dashboard.presentation;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.dashboard.application.DashboardBriefing;
import com.checkon.dashboard.application.DashboardBriefingService;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
	private final DashboardBriefingService briefingService;

	public DashboardController(DashboardBriefingService briefingService) {
		this.briefingService = briefingService;
	}

	@GetMapping("/briefing")
	DashboardBriefing briefing(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
	) {
		return briefingService.getBriefing(principal.teacherProfileId(), date);
	}
}
