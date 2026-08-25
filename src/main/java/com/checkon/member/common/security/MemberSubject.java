package com.checkon.member.common.security;

import java.util.UUID;

/**
 * 컨트롤러가 받는 요청 주체. 누가 호출했는지(학생 앱/학부모 앱)는 여기서 알 수 없고,
 * 역할만 담는다 — 서비스는 역할로만 분기한다.
 *
 * @param accountId        계정 식별자
 * @param role             member 역할
 * @param sessionId        세션 식별자
 * @param studentProfileId {@code role=STUDENT} 일 때만 non-null
 * @param parentProfileId  {@code role=PARENT} 일 때만 non-null
 * @param activationStatus {@code role=STUDENT} 일 때만 의미가 있다.
 *                         🔴 활성화 테이블이 아직 없어 PR2 전까지는 항상 {@code null} 이다.
 *                         모르는 값을 지어내지 않는다
 */
public record MemberSubject(
	UUID accountId,
	MemberRole role,
	UUID sessionId,
	UUID studentProfileId,
	UUID parentProfileId,
	String activationStatus
) {

	public UUID requireStudentProfileId() {
		if (studentProfileId == null) {
			throw new IllegalStateException("student profile id is required but absent");
		}
		return studentProfileId;
	}

	public UUID requireParentProfileId() {
		if (parentProfileId == null) {
			throw new IllegalStateException("parent profile id is required but absent");
		}
		return parentProfileId;
	}
}
