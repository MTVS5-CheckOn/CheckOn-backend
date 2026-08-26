package com.checkon.member.learning.domain;

/**
 * {@code member_attempt_events.event_type} 의 5종.
 *
 * <p>🔴 값은 {@code ck_member_attempt_events_type} CHECK 와 그대로 대응한다(V40:156-158).
 * 새 이벤트 종류를 추가하려면 마이그레이션 먼저다.</p>
 */
public enum MemberAttemptEventType {
	STARTED,
	RESUMED,
	PROGRESS,
	SUBMITTED,
	SCORED
}
