package com.checkon.member.learning.domain;

/**
 * {@code member_attempts.status} 의 상태 3종.
 *
 * <p>🔴 문자열은 {@code ck_member_attempts_status} CHECK 와 그대로 대응한다(V40:83-84) —
 * enum 이름과 DB 문자열이 갈리면 저장 자체가 실패한다. 새 값을 추가하려면 마이그레이션 먼저다.</p>
 */
public enum MemberAttemptStatus {
	IN_PROGRESS,
	SUBMITTED,
	SCORED
}
