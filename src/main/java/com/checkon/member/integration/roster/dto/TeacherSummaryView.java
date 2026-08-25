package com.checkon.member.integration.roster.dto;

import java.util.UUID;

/**
 * 학생·학부모에게 보여줄 강사 요약.
 *
 * <p>🔴 {@code subject} 는 <b>항상 {@code null}</b> 이다. 원본이 없어서가 아니라 정책이 없어서다 —
 * 계약 {@code member-api.yaml} 이 <i>"원본은 {@code class_groups.subject}(V13) 뿐이고
 * 학생·학부모는 {@code class_groups} 에 SELECT 정책이 없다"</i>고 적었다. 지어내지 않는다.</p>
 *
 * <p>🔴 {@code academyName} 필드는 <b>두지 않는다.</b> 계약이 "백엔드 어디에도 원본이 없어서
 * 필드 자체를 두지 않는다"고 명시했다 — {@code teacher_profiles} 는 {@code display_name} 뿐이다
 * (V4:54-58). 없는 필드를 {@code null} 로라도 만들면 프론트가 언젠가 채워질 값으로 오해한다.</p>
 */
public record TeacherSummaryView(UUID teacherId, String displayName, String subject) {

	public static TeacherSummaryView of(UUID teacherId, String displayName) {
		return new TeacherSummaryView(teacherId, displayName, null);
	}
}
