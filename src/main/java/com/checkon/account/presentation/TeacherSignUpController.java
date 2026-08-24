package com.checkon.account.presentation;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.application.TeacherSignUpService;
import com.checkon.account.application.TeacherSignUpService.TeacherSignUpResult;
import com.checkon.account.domain.AccountRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 강사가 초대 없이 가입할 수 있는 공개 HTTP 진입점이다.
 *
 * <p>HTTP 요청 검증과 응답 변환만 담당하며, 가입 트랜잭션과 중복 정책은
 * {@link TeacherSignUpService}에 위임한다.</p>
 */
@RestController
@RequestMapping("/api/v1/auth/sign-up/teachers")
public class TeacherSignUpController {

	private final TeacherSignUpService teacherSignUpService;

	public TeacherSignUpController(TeacherSignUpService teacherSignUpService) {
		this.teacherSignUpService = teacherSignUpService;
	}

	@PostMapping
	public ResponseEntity<TeacherSignUpResponse> signUp(
		@Valid @RequestBody TeacherSignUpRequest request
	) {
		TeacherSignUpResult result = teacherSignUpService.signUp(
			request.email(),
			request.password(),
			request.displayName()
		);
		return ResponseEntity.created(
			URI.create("/api/v1/accounts/" + result.accountId())
		).body(new TeacherSignUpResponse(new TeacherSignUpData(
			result.accountId(),
			result.teacherId(),
			result.role(),
			result.email()
		)));
	}

	public record TeacherSignUpRequest(
		// 이메일의 공백 제거와 형식 검사는 EmailAddress가 한 규칙으로 처리한다.
		@NotBlank @Size(max = 320) String email,
		@NotBlank @Size(min = 8) String password,
		@NotBlank @Size(max = 80) String displayName
	) {
	}

	public record TeacherSignUpResponse(TeacherSignUpData data) {
	}

	public record TeacherSignUpData(
		UUID accountId,
		UUID teacherId,
		AccountRole role,
		String email
	) {
	}
}
