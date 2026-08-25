package com.checkon.member.auth.presentation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 계약 {@code StudentSignUpRequest}(member-api.yaml:1653-1662).
 *
 * <p>🔴 {@code marketingAgreed} 는 계약이 {@code default: false} 다. record 의 {@code Boolean}
 * 은 키가 없으면 null 이 되므로 읽는 쪽에서 null 을 false 로 본다 — 여기서 원시형
 * {@code boolean} 을 쓰면 "안 보냈다"와 "false 로 보냈다"가 구분되지 않는다.</p>
 */
public record StudentSignUpRequest(
	@NotBlank @Email @Size(max = 320) String email,
	@NotBlank @Size(min = 8, max = 200) String password,
	@NotBlank @Size(min = 1, max = 100) String name,
	@NotNull @Min(1) @Max(3) Integer grade,
	@NotNull @AssertTrue Boolean termsAgreed,
	Boolean marketingAgreed
) {
}
