package com.checkon.member.auth.presentation;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.auth.application.MemberSignUpCommand;
import com.checkon.member.auth.application.MemberSignUpResult;
import com.checkon.member.auth.application.ParentSignUpService;
import com.checkon.member.auth.application.StudentLoginService;
import com.checkon.member.auth.application.StudentSignUpService;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.integration.account.MemberAuthentication;
import com.checkon.member.integration.account.MemberLoginAdapter;

import jakarta.validation.Valid;

/**
 * 가입·로그인. 인증 없이 접근하는 3개 엔드포인트다.
 *
 * <p>🔴 메서드 이름을 계약 {@code operationId} 와 <b>글자 그대로</b> 맞춘다
 * ({@code signUpStudent} · {@code signUpParent} · {@code loginStudent}).
 * 이름이 어긋나면 계약 대조 테스트가 잡지 못하는 자리에서 문서와 코드가 갈라진다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/auth")
public class MemberAuthController {

	private final StudentSignUpService studentSignUpService;
	private final ParentSignUpService parentSignUpService;
	private final StudentLoginService studentLoginService;
	private final MemberLoginAdapter loginAdapter;

	public MemberAuthController(
		StudentSignUpService studentSignUpService,
		ParentSignUpService parentSignUpService,
		StudentLoginService studentLoginService,
		MemberLoginAdapter loginAdapter
	) {
		this.studentSignUpService = studentSignUpService;
		this.parentSignUpService = parentSignUpService;
		this.studentLoginService = studentLoginService;
		this.loginAdapter = loginAdapter;
	}

	@PostMapping("/students/sign-up")
	public ResponseEntity<MemberResponse<MemberSignUpResult>> signUpStudent(
		@Valid @RequestBody StudentSignUpRequest request
	) {
		MemberSignUpResult result = studentSignUpService.signUp(new MemberSignUpCommand(
			request.email(), request.password(), request.name(), request.grade()));
		return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.of(result));
	}

	@PostMapping("/parents/sign-up")
	public ResponseEntity<MemberResponse<MemberSignUpResult>> signUpParent(
		@Valid @RequestBody ParentSignUpRequest request
	) {
		// 🔴 grade 를 null 로 넘긴다. 학부모에는 학년이 없다.
		MemberSignUpResult result = parentSignUpService.signUp(new MemberSignUpCommand(
			request.email(), request.password(), request.name(), null));
		return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.of(result));
	}

	@PostMapping("/students/login")
	public ResponseEntity<MemberResponse<MemberAuthResultResponse>> loginStudent(
		@Valid @RequestBody StudentLoginRequest request
	) {
		MemberAuthentication result =
			studentLoginService.login(request.studentPublicId(), request.password());
		// 🔴 refresh 원문은 쿠키로만 나간다. 본문에 넣지 않는다.
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE,
				loginAdapter.refreshCookie(result.refreshToken()).toString())
			.body(MemberResponse.of(new MemberAuthResultResponse(
				result.accessToken(),
				result.accessTokenExpiresAt(),
				new MemberAuthResultResponse.Account(
					result.accountId(),
					result.role().name(),
					result.email(),
					// 🔴 계약 member-api.yaml:1729 — 학생·학부모는 항상 null.
					null))));
	}
}
