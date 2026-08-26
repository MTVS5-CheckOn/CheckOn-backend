package com.checkon.member.membership.domain;

import com.checkon.member.common.security.MemberRole;

/**
 * 초대 코드가 지정한 대상 역할. V38 {@code ck_member_invitation_codes_role} 이 두 값만 허용한다.
 *
 * <p>MB-04 CONFIRMED — 코드는 <b>역할이 지정된</b> 1회용이다. 학생용 코드를 학부모가 쓸 수 없다.</p>
 */
public enum InvitationTargetRole {

	STUDENT,
	PARENT;

	public static InvitationTargetRole of(MemberRole role) {
		return role == MemberRole.STUDENT ? STUDENT : PARENT;
	}
}
