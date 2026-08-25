package com.checkon.member.membership.presentation;

/** {@code POST /member/parents/me/children/verification} 요청 본문. */
public record ChildVerificationRequest(String studentPublicId) {
}
