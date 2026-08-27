package com.checkon.member.common.notification;

/**
 * 알림 타입. 🔴 계약({@code member-api.yaml:2364}) 의 enum 5개를 그대로 반영한다.
 *
 * <p>V41 의 {@code ck_member_notifications_type} 이 최종 강제한다.</p>
 *
 * <p>이 PR 이 실제로 발행하는 것은 {@link #CHILD_LINKED} 하나뿐이다:</p>
 * <ul>
 *   <li>{@link #REPORT_PUBLISHED} · {@link #CONSULTATION_ANSWERED} — PR9 · PR8 이 발행한다</li>
 *   <li>{@link #QUESTION_ANSWERED} — 강사 답변 API 가 계약에 없어 이 PR 은 발행하지 않는다
 *       (open item MB-44)</li>
 *   <li>{@link #LEARNING_SUBMITTED} — 수신자(학부모) 계정을 학생 컨텍스트로 해석할 정책이
 *       없어 이 PR 은 발행하지 않는다 (open item MB-45)</li>
 * </ul>
 */
public enum NotificationType {
	REPORT_PUBLISHED,
	CONSULTATION_ANSWERED,
	QUESTION_ANSWERED,
	LEARNING_SUBMITTED,
	CHILD_LINKED
}
