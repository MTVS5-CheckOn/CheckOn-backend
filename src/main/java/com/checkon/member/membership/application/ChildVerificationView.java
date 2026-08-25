package com.checkon.member.membership.application;

import com.checkon.member.membership.domain.ChildVerificationReason;

/**
 * 자녀 사전 확인 결과. 🔴 <b>등록을 보장하지 않는다</b> — {@code registrable=true} 를 받은 뒤
 * 1ms 만에 다른 학부모가 먼저 등록할 수 있다(계약 §5-2). 그래서 "등록 가능합니다"가 아니라
 * 지금 보이는 상태만 돌려준다.
 *
 * @param name 🔴 <b>마스킹된 값만</b>. 원본은 어떤 경우에도 나가지 않는다
 */
public record ChildVerificationView(
	boolean registrable,
	String name,
	Integer grade,
	ChildVerificationReason reason
) {
}
