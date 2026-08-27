package com.checkon.member.profile.application.dto;

import java.util.List;
import java.util.UUID;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.membership.application.ChildView;

/**
 * 계약 {@code ParentProfile} (member-api.yaml:2376-2389).
 *
 * <p>🔴 {@code children} 은 PR4 의 {@link ChildView} 를 그대로 담는다 —
 * 등록 응답·자녀 목록·프로필이 <b>같은 모양</b>이어야 프론트가 한 어댑터로 처리한다.
 * assembler 를 재사용하므로 자녀별 강사·이름 채우기 규칙도 자동으로 같다(MB-36).</p>
 */
public record ParentProfileResponse(
	UUID parentId,
	String name,
	String email,
	List<ChildView> children,
	List<MemberTeacherSummary> teachers,
	boolean notificationsEnabled
) {
}
