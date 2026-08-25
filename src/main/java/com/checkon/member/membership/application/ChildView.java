package com.checkon.member.membership.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/**
 * 자녀 한 명. 계약의 {@code Child} 스키마와 같은 모양이다.
 *
 * @param name     원본은 {@code member_display_names.display_name}(V39) 다 —
 *                 🔴 {@code student_profiles.alias} 가 아니다. alias 는 가입 시 초기값으로만
 *                 복사되고 이후 member API 는 표시 이름 테이블만 읽는다(설계 §3-1 교차 전수표)
 * @param teachers 🔴 <b>{@code null} 은 "없음"이 아니라 "모른다"</b>이다.
 *                 학부모 컨텍스트에는 {@code teacher_student_relationships} SELECT 정책이 없다
 *                 (V38:126-135 — 불변식 4번 재귀 때문에 의도적으로 뺐다). 빈 배열로 내리면
 *                 "이 자녀는 강사가 없다"는 <b>틀린 사실</b>을 단정하게 된다. MB-36 으로 등재했다
 */
public record ChildView(
	UUID studentId,
	String studentPublicId,
	String name,
	Integer grade,
	MemberActivationStatus activationStatus,
	Instant linkedAt,
	List<TeacherSummaryView> teachers
) {
}
