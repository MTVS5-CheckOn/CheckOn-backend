package com.checkon.member.membership.presentation;

/** {@code POST /member/parents/me/children} 요청 본문. */
public record ChildRegistrationRequest(String studentPublicId) {
}
