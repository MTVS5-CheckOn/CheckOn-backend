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

	/**
	 * 🔴 세 관계 테이블의 {@code CHECK} 는 {@code ('ACTIVE','ENDED')} 뿐이라 이 값은 <b>행에
	 * 저장될 수 없다.</b> 그런데도 남겨 두는 이유는
	 * {@code uq_teacher_student_relationships_current_teacher_student}(V33:5-7)의 부분 인덱스
	 * 술어가 {@code status IN ('ACTIVE','PAUSED')} 이기 때문이다.
	 *
	 * <p>PostgreSQL 의 부분 인덱스 추론은 텍스트 일치가 아니라 <b>술어 함의</b>로 판정하므로
	 * 논리적으로 동치이기만 하면 통과한다. 그래도 <b>인덱스와 똑같이 쓰는 것이 안전한 관행</b>이다 —
	 * 동치 판정이 늘 직관대로 되지는 않는다. 값 공간을 줄이면 다음 사람이 술어에서 PAUSED 를
	 * 빼기 쉬워지고, 그때 추론이 인덱스를 못 찾으면 23505 가 그대로 올라온다.
	 */
	PAUSED,

	ENDED
}
