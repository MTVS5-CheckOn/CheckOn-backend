package com.checkon.member.auth.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 계약 {@code StudentLoginRequest}(member-api.yaml:1701-1710).
 *
 * <p>🔴 {@code studentPublicId} 에 형식 제약을 걸지 않는다. 계약 {@code :1708-1709} 가
 * <i>"서버가 정규화한다"</i> 라고 적었고, 형식 위반을 400 으로 되돌리면 그 자체가
 * "이 형식은 존재할 수 없다"는 신호가 되어 열거에 쓰인다. 정규화 실패도
 * 로그인 실패와 <b>같은 401</b> 로 나가야 한다.</p>
 */
public record StudentLoginRequest(
	@NotBlank String studentPublicId,
	@NotBlank @Size(min = 8, max = 200) String password
) {
}
