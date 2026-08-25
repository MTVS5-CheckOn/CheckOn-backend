package com.checkon.member.membership.application;

import java.time.Instant;

import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/** 초대 코드 검증 결과. 계약의 {@code InviteVerification} 과 같다. */
public record InviteVerificationView(
	boolean valid,
	TeacherSummaryView teacher,
	Instant expiresAt
) {
}
