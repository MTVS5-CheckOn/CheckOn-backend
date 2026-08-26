package com.checkon.member.membership.presentation;

/** 초대 검증·등록 공통 요청 본문. 🔴 평문 코드는 서비스에서 즉시 해시가 된다. */
public record InvitationCodeRequest(String code) {
}
