package com.checkon.member.integration.roster.dto;

import java.util.UUID;

/**
 * 공개 학생 ID 로 찾은 학생. member 전용 immutable record 다 — roster 엔티티를 밖으로 내보내지 않는다.
 *
 * @param studentProfileId {@code student_profiles.id}
 * @param accountId        {@code student_profiles.account_id}. 표시 이름을 읽을 때 범위 대상이 된다
 * @param alias            🔴 원본은 {@code student_profiles.alias}(V6:6) — <b>실명이 아니다.</b>
 *                         실명은 {@code student_personal_information.real_name}(V12:3) 인데
 *                         RLS 가 강사 전용이라 학부모 컨텍스트에서는 0건이다
 * @param grade            {@code student_profiles.grade}. 없으면 {@code null}
 */
public record StudentIdentity(
	UUID studentProfileId,
	UUID accountId,
	String alias,
	Integer grade
) {
}
