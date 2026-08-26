package com.checkon.member.auth.application;

import java.util.UUID;

/**
 * 세션에 실리는 강사 요약.
 *
 * <p>🔴 {@code subject} 는 항상 {@code null} 이다. 근거는 계약
 * {@code src/main/resources/openapi/member-api.yaml:1648-1650} 의 문구 그대로다 —
 * <i>"원본은 {@code class_groups.subject}(V13) 뿐이고 학생·학부모는 {@code class_groups} 에
 * SELECT 정책이 없다. 현재는 항상 {@code null} 이다."</i></p>
 *
 * <p>🔴 <b>원본이 없는 게 아니라 정책이 없는 것</b>이다. 언젠가 설계 §6-4-2 범위 세션 변수로
 * 열 수 있다. "원본이 없다"로 읽으면 다음 사람이 영영 못 채우는 값으로 오해한다.</p>
 */
public record MemberTeacherSummary(UUID teacherId, String displayName, String subject) {
}
