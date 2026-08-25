package com.checkon.member.integration.account;

import java.util.UUID;

/**
 * 계정 생성 결과. member 가 이후 단계(프로필·활성화)에서 쓰는 값만 담는다.
 *
 * @param accountId 확정된 계정 식별자
 * @param email     정규화된 이메일 (소문자·trim)
 */
public record MemberAccountCreation(UUID accountId, String email) {
}
