package com.checkon.member.membership.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/**
 * 자녀 한 명. 계약의 {@code Child} 스키마와 같은 모양이다.
 *
 * <p>🔴 {@code teachers} 는 V40(MB-36) 이 정책을 열면서 <b>다시 필드로 돌아왔다.</b>
 * {@code ChildViewAssembler} 가 {@code withVerifiedChildScope} 안에서 채워야 <b>비어 있지 않다</b> —
 * 범위를 열지 않고 부르면 조용히 {@code []} 가 된다(계약 위반은 아니지만 진짜 「없음」과 구분되지 않는다).
 * PR3 결함 3(「teachers 가 조용히 빈 배열 — 200 이라 더 조용하다」)과 같은 위험이라
 * assembler 한 곳에서만 채운다.</p>
 *
 * <p>🔴 {@code grade} 는 계약이 {@code nullable: true} 라 <b>키가 존재하고 값이 null</b>
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
	List<TeacherSummaryView> teachers,
	Instant linkedAt
) {
}
