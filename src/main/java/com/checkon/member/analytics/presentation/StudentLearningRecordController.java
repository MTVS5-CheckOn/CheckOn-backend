package com.checkon.member.analytics.presentation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.analytics.application.StudentLearningRecordQueryService;
import com.checkon.member.analytics.application.dto.LearningRecordListPage;
import com.checkon.member.analytics.application.dto.LearningRecordResponse;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;

/** 학생 학습기록 조회. 상세는 자기 것만 나온다(RLS). */
@RestController
@RequestMapping("/api/v1/member/students/me/learning-records")
public class StudentLearningRecordController {

	private final StudentLearningRecordQueryService service;

	public StudentLearningRecordController(StudentLearningRecordQueryService service) {
		this.service = service;
	}

	@GetMapping
	public MemberResponse<LearningRecordListPage> list(
		@CurrentMember MemberSubject subject,
		@RequestParam(value = "month", required = false) String month,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", defaultValue = "20") int limit
	) {
		return MemberResponse.of(service.list(subject, month, cursor, limit));
	}

	@GetMapping("/{recordId}")
	public MemberResponse<LearningRecordResponse> detail(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID recordId
	) {
		return MemberResponse.of(service.getDetail(subject, recordId));
	}
}
