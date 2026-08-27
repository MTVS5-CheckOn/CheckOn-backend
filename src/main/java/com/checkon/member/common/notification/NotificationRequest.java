package com.checkon.member.common.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * 알림 발행 요청.
 *
 * <p>🔴 {@code sourceType} · {@code sourceId} 는 "무엇이 이 알림을 만들었나"다.
 * 재실행해도 같은 값이어야 한다 — {@code uq_member_notifications_source} 가
 * {@code (source_type, source_id, recipient_account_id)} 를 유일하게 강제한다.</p>
 *
 * <p>🔴 {@code title} · {@code body} 는 서버가 조립한다. 호출자가 임의 문자열을 넘길 수 없는
 * 폼이 아니다 — <b>이 record 는 이미 조립된 값을 받는다</b>. 조립 정책은 각 발행자
 * (자녀 등록 등)이 자기 도메인 안에서 정한다.</p>
 *
 * @param recipientAccountId 수신자 계정
 * @param type               알림 타입 (계약 enum · V41 CHECK)
 * @param title              200자 이내
 * @param body               nullable
 * @param targetStudentId    관련 학생 id (nullable)
 * @param targetResourceId   관련 리소스 id (nullable)
 * @param sourceType         발행 원본 유형 — 48자 이내, 예: {@code "parent_student_relationship"}
 * @param sourceId           발행 원본 id
 */
public record NotificationRequest(
	UUID recipientAccountId,
	NotificationType type,
	String title,
	String body,
	UUID targetStudentId,
	UUID targetResourceId,
	String sourceType,
	UUID sourceId
) {

	public NotificationRequest {
		Objects.requireNonNull(recipientAccountId, "recipientAccountId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(sourceType, "sourceType");
		Objects.requireNonNull(sourceId, "sourceId");
	}
}
