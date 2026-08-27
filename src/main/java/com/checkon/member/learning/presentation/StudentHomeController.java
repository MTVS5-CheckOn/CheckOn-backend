package com.checkon.member.learning.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.learning.application.StudentWorksheetService;
import com.checkon.member.learning.application.dto.StudentHomeResponse;

/** 학생 홈 조회. 데이터 없음은 오류가 아니라 계약에 정해진 null/빈 배열이다. */
@RestController
@RequestMapping("/api/v1/member/students/me/home")
public class StudentHomeController {

	private final StudentWorksheetService worksheetService;

	public StudentHomeController(StudentWorksheetService worksheetService) {
		this.worksheetService = worksheetService;
	}

	@GetMapping
	public MemberResponse<StudentHomeResponse> getStudentHome(
		@CurrentMember MemberSubject subject
	) {
		return MemberResponse.of(worksheetService.getHome(subject));
	}
}
