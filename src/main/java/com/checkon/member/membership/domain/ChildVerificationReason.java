package com.checkon.member.membership.domain;

/**
 * 자녀 사전 확인이 {@code registrable=false} 인 이유. 계약의 enum 과 같은 집합이다.
 *
 * <p>🔴 {@code ALREADY_LINKED} 는 <b>호출한 학부모 자신이 이미 연결돼 있을 때만</b> 나온다.
 * 다른 학부모의 연결은 RLS 정책상 보이지 않는다 —
 * {@code parent_student_relationships_member_parent_select}(V38:107-112) 가
 * {@code parent_id = current_checkon_parent_id()} 로 격리하기 때문이다.
 * 그 경우는 사전 확인에서 {@code registrable=true} 로 보이고 등록 시점에 409 로 갈린다.
 * 사전 확인이 <b>등록을 보장하지 않는다</b>는 계약(§5-2)과 어긋나지 않는다. MB-37 로 등재했다.</p>
 */
public enum ChildVerificationReason {

	ALREADY_LINKED,
	NOT_FOUND
}
