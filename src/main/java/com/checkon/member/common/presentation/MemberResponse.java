package com.checkon.member.common.presentation;

/**
 * member 성공 봉투. 계약 §7-1 의 {@code {"data": <payload>}} 형태다.
 * 기존 {@code /api/v1/auth} 응답이 이미 같은 모양이라 신규 규약이 아니라 선례를 따르는 것이다.
 */
public record MemberResponse<T>(T data) {

	public static <T> MemberResponse<T> of(T data) {
		return new MemberResponse<>(data);
	}
}
