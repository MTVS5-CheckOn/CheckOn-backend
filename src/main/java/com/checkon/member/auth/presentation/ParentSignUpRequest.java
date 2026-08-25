package com.checkon.member.auth.presentation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 계약 {@code ParentSignUpRequest}(member-api.yaml:1664-1672).
 *
 * <p>🔴 학부모에는 {@code grade} 가 없다. 학생 요청과 한 record 로 합치지 않는다 —
 * 합치면 학부모 요청에 grade 를 실어도 조용히 통과한다.</p>
 */
public record ParentSignUpRequest(
	@NotBlank @Email @Size(max = 320) String email,
	@NotBlank @Size(min = 8, max = 200) String password,
	@NotBlank @Size(min = 1, max = 100) String name,
	@NotNull @AssertTrue Boolean termsAgreed,
	Boolean marketingAgreed
) {
}
