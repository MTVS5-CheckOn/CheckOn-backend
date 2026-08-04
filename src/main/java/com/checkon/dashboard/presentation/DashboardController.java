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
import com.checkon.dashboard.application.DashboardCalendar;
import com.checkon.dashboard.application.DashboardCalendarService;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
	private final DashboardBriefingService briefingService;
	private final DashboardCalendarService calendarService;

	public DashboardController(
		DashboardBriefingService briefingService,
		DashboardCalendarService calendarService
	) {
		this.briefingService = briefingService;
		this.calendarService = calendarService;
	}

	@GetMapping("/briefing")
	DashboardBriefing briefing(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
	) {
		return briefingService.getBriefing(principal.teacherProfileId(), date);
	}

	@GetMapping("/calendar")
	DashboardCalendar calendar(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startedAt,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endedAt
	) {
		return calendarService.getCalendar(
			principal.teacherProfileId(), startedAt, endedAt
		);
	}
}
