package com.checkon.member.membership.application;

import java.time.Instant;
import java.util.UUID;

import com.checkon.member.auth.domain.MemberActivationStatus;

/**
 * 자녀 한 명. 계약의 {@code Child} 스키마와 같은 모양이다.
 *
 * <p>🔴 {@code teachers} 는 <b>키 자체를 넣지 않는다</b>(MB-36). 계약의 {@code Child} 는
 * {@code required: [studentId, studentPublicId, name, activationStatus]} 라 필수가 아니고,
 * {@code teachers: { type: array, items: TeacherSummary }} 에는 <b>{@code nullable} 이 없다</b> —
 * 즉 {@code null} 로 내려보내면 <b>계약 위반</b>이다. 그렇다고 {@code []} 로 채울 수도 없다:
 * 그건 「이 자녀는 강사가 없다」는 <b>주장</b>인데, 실제로는 <b>못 보는 것</b>이다.
 * {@code teacher_student_relationships} 의 SELECT 정책은 {@code current_checkon_student_id()} 를
 * 요구하는데(V38:125-130) 학부모는 그 학생이 아니다. 남는 선택지는 키를 빼는 것뿐이다.
 * {@code MemberSessionView.notificationsEnabled}(MB-32)와 같은 판정이다.</p>
 *
 * <p>🔴 {@code grade} 는 다르다 — 계약이 {@code nullable: true} 라 <b>키가 존재하고 값이 null</b>
 * 이어야 한다. 그래서 「null 이면 키를 뺀다」를 레코드 전체에 거는 방식(NON_NULL)을 쓰지 않고,
 * 채울 수 없는 필드만 <b>선언에서 뺀다.</b></p>
 *
 * @param name 원본은 {@code member_display_names.display_name}(V39) 다 —
 *             🔴 {@code student_profiles.alias} 가 아니다. alias 는 가입 시 초기값으로만
 *             복사되고 이후 member API 는 표시 이름 테이블만 읽는다(설계 §3-1 교차 전수표)
 */
public record ChildView(
	UUID studentId,
	String studentPublicId,
	String name,
	Integer grade,
	MemberActivationStatus activationStatus,
	Instant linkedAt
) {
}
