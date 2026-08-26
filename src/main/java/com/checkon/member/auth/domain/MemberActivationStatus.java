package com.checkon.member.auth.domain;

/**
 * 학생 활성화 상태. 계약 {@code StudentActivationStatus} 및
 * {@code ck_member_student_activation_status} CHECK 과 같은 집합이다.
 */
public enum MemberActivationStatus {
	PENDING_PARENT_LINK,
	ACTIVE,
	DEACTIVATED
}
