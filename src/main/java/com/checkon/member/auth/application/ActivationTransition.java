package com.checkon.member.auth.application;

/**
 * {@code member_student_activation} 전이 결과.
 *
 * <p>🔴 「UPDATE 가 0행이었다」에는 서로 다른 두 사건이 섞여 있다 — 이미 {@code ACTIVE} 라서
 * 갱신할 것이 없는 경우와, RLS 정책이 막아 아무것도 못 본 경우다. 앞의 것은 정상이고 뒤의 것은
 * 장애다. 호출부가 둘을 구분할 수 있도록 <b>값으로</b> 돌려준다 — 설명되지 않는 0행은
 * 여기서 이미 예외로 올라간다(설계 §12-2 조용한 절단 금지).</p>
 */
public enum ActivationTransition {

	/** {@code PENDING_PARENT_LINK} → {@code ACTIVE} 로 실제 전이됐다. */
	ACTIVATED,

	/** 이미 {@code ACTIVE} 였다. 재등록 경로에서 정상이며 0행이 설명된다. */
	ALREADY_ACTIVE
}
