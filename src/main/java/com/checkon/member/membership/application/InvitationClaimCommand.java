package com.checkon.member.membership.application;

/** 초대 등록 요청 한 건. {@code rawBody} 는 멱등 해시용 <b>원문</b>이다. */
public record InvitationClaimCommand(String code, String idempotencyKey, String rawBody) {
}
