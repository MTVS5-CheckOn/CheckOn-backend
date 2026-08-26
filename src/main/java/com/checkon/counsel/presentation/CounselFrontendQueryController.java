package com.checkon.counsel.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.application.CounselFrontendQueryService;
import com.checkon.counsel.application.CounselFrontendQueryService.CommunicationView;
import com.checkon.counsel.application.CounselFrontendQueryService.InquiryView;
import com.checkon.counsel.application.CreateCounselDraftCommand.Fact;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.global.presentation.PagedResponse;

@RestController
@RequestMapping("/api/v1")
public class CounselFrontendQueryController {

	private final CounselFrontendQueryService service;

	public CounselFrontendQueryController(CounselFrontendQueryService service) {
		this.service = service;
	}

	@GetMapping("/guardians")
	PagedResponse<GuardianResponse> guardians(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		var result = service.guardians(principal, page, size);
		return PagedResponse.of(
			result.content().stream().map(GuardianResponse::from).toList(),
			result.page(), result.size(), result.totalElements()
		);
	}

	@GetMapping("/guardians/{parentId}/communications")
	PagedResponse<CommunicationResponse> communications(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID parentId,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		var result = service.communications(principal, parentId, page, size);
		return PagedResponse.of(
			result.content().stream().map(CommunicationResponse::from).toList(),
			result.page(), result.size(), result.totalElements()
		);
	}

	@GetMapping("/counsel/inquiries")
	PagedResponse<InquiryResponse> inquiries(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		var result = service.inquiries(principal, page, size);
		return PagedResponse.of(
			result.content().stream().map(this::toInquiryResponse).toList(),
			result.page(), result.size(), result.totalElements()
		);
	}

	public record GuardianResponse(
		UUID parentId,
		String displayName,
		List<LinkedStudentResponse> students,
		List<GuardianLabelResponse> labels,
		long communicationCount,
		Instant latestCommunicationAt,
		LatestInquiryResponse latestInquiry
	) {
		static GuardianResponse from(CounselFrontendQueryService.GuardianView view) {
			LatestInquiryResponse latest = view.latestInquiry() == null ? null : new LatestInquiryResponse(
				view.latestInquiry().topic(), view.latestInquiry().urgency(),
				view.latestInquiry().jobId(), view.latestInquiry().jobPhase()
			);
			return new GuardianResponse(
				view.parentId(), view.displayName(),
				view.students().stream().map(student -> new LinkedStudentResponse(
					student.studentId(), student.studentName(), student.classId(), student.className()
				)).toList(),
				view.labels().stream().map(label -> new GuardianLabelResponse(
					label.axis(), label.value(), label.updatedAt()
				)).toList(),
				view.communicationCount(), view.latestCommunicationAt(), latest
			);
		}
	}

	public record LinkedStudentResponse(UUID studentId, String studentName, UUID classId, String className) { }
	public record GuardianLabelResponse(String axis, String value, Instant updatedAt) { }
	public record LatestInquiryResponse(CounselTopic topic, CounselUrgency urgency, String jobId, String jobPhase) { }

	public record CommunicationResponse(
		String recordId, String direction, Instant occurredAt, String body,
		UUID studentId, String studentName, String inquiryRef,
		CounselTopic topic, CounselUrgency urgency, boolean actuallySent,
		String jobId, String jobPhase
	) {
		static CommunicationResponse from(CommunicationView row) {
			return new CommunicationResponse(
				row.recordId(), row.direction(), row.occurredAt(), row.body(),
				row.studentId(), row.studentName(), row.inquiryRef(),
				row.topic(), row.urgency(),
				row.actuallySent(), row.jobId(), row.jobPhase()
			);
		}
	}

	public record InquiryResponse(
		String inquiryRef, UUID parentId, UUID studentId, String studentName,
		UUID classId, String className, CounselTopic topic, CounselUrgency urgency,
		Instant receivedAt, String rawText, List<String> labels, String periodLabel,
		List<Fact> facts, boolean draftJobCreated, String jobId, String jobPhase
	) { }

	private InquiryResponse toInquiryResponse(InquiryView row) {
		return new InquiryResponse(
			row.inquiryRef(), row.parentId(), row.studentId(), row.studentName(),
			row.classId(), row.className(), row.topic(), row.urgency(), row.receivedAt(), row.rawText(),
			row.labels(), row.periodLabel(), row.facts(), row.draftJobCreated(), row.jobId(), row.jobPhase()
		);
	}
}
