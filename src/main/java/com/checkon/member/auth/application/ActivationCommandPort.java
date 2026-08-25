package com.checkon.member.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_student_activation} 의 <b>쓰기</b> 문. 소유는 {@code auth} 다.
 *
 * <p>🔴 {@code membership} 이 자녀 등록에서 이 포트로 전이시킨다 — 설계 §3-1 의 교차 전수표가
 * 「직접 UPDATE 금지」로 못 박은 자리다. 소유 sub-context 밖에서 같은 테이블에 UPDATE 문을
 * 또 쓰면 상태 전이 규칙이 두 벌이 된다.</p>
 *
 * <p>🔴 호출자는 <b>같은 트랜잭션에서</b> 범위를 먼저 열어야 한다
 * ({@code MemberDatabaseContext.withVerifiedChildScope}). V39 의
 * {@code member_student_activation_parent_scope_update} 가
 * {@code current_checkon_parent_id()} 와 {@code current_checkon_scope_student_id()} 를
 * 동시에 요구하기 때문이다(설계 §6-4-3). 열지 않으면 예외가 아니라 <b>0행</b>이다.</p>
 */
public interface ActivationCommandPort {

	/**
	 * 학생을 활성화한다.
	 *
	 * @return 실제 전이됐는지, 이미 활성이었는지
	 * @throws com.checkon.member.common.error.MemberException
	 *         어느 쪽으로도 설명되지 않는 0행일 때. 조용히 넘기지 않는다
	 */
	ActivationTransition activate(UUID studentProfileId, Instant now);
}
