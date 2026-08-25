package com.checkon.member.common.persistence;

/**
 * V33 관계 테이블들이 공유하는 상태값.
 *
 * <p>🔴 문자열 리터럴로 흘리지 않는다(코드 규칙 §2). {@code 'ACTIVE'} 가 SQL 과 자바 양쪽에
 * 흩어지면 한쪽만 고쳐도 아무 경고가 없다.</p>
 *
 * <p>🔴 {@code membership} 이 아니라 {@code common} 에 둔다. {@code integration/roster} 어댑터도
 * 같은 값을 쓰는데, 그쪽이 sub-context 를 import 하면 <b>membership → integration → membership</b>
 * 순환이 생긴다. 기존 {@code integration/*} 은 예외 없이 {@code common} 만 참조한다(실측).</p>
 */
public enum RelationshipStatus {

	ACTIVE,
	PAUSED,
	ENDED
}
