package com.checkon.member.profile.application.dto;

import java.util.List;
import java.util.UUID;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.auth.domain.MemberActivationStatus;

/**
 * 계약 {@code StudentProfile} (member-api.yaml:2071-2084).
 *
 * <p>🔴 {@code grade} 는 계약이 required 로 뒀지만 nullable 이 아니라 <b>null 로도 못 채운다.</b>
 * {@code student_profiles.grade} 는 실제 nullable — 불일치 발견 시 지어내지 않고 그대로 null 을
 * 넣는다(open item · 계약 수정 별도). NON_NULL 정책이 아니므로 key 는 남고 값이 null 이다.</p>
 *
 * <p>🔴 {@code parentLinked} 는 <b>{@code activationStatus == ACTIVE}</b> 로 파생한다.
 * {@code parent_student_relationships} 는 학생 SELECT 정책이 없어(V38 전수 #6) 조회하면 조용히
 * false 가 나온다. 활성화 상태가 최종 신호다.</p>
 */
public record StudentProfileResponse(
	UUID studentId,
	String studentPublicId,
	String name,
	Integer grade,
	MemberActivationStatus activationStatus,
	boolean parentLinked,
	List<MemberTeacherSummary> teachers,
	boolean notificationsEnabled
) {
}
