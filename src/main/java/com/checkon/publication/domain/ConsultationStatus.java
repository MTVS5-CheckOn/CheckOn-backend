package com.checkon.publication.domain;

import java.util.Set;

/**
 * {@code member_consultations.status} 어휘. 🔴 V44 의
 * {@code ck_member_consultations_status} 에 있는 <b>다섯 개 그대로</b>다 — 늘리지 않는다.
 */
public final class ConsultationStatus {

	public static final String SUBMITTED = "SUBMITTED";
	public static final String REVIEWING = "REVIEWING";
	public static final String ANSWERED = "ANSWERED";
	public static final String CLOSED = "CLOSED";
	public static final String CANCELLED = "CANCELLED";

	/**
	 * 🔴 답변을 <b>받을 수 있는</b> 상태. {@code ANSWERED} 는 빠져 있다 — 두 번째 답변은
	 * 409 다(계약 {@code member-teacher-api.yaml}). {@code CLOSED}·{@code CANCELLED} 도
	 * 끝난 상담이라 답할 수 없다.
	 *
	 * <p>🔴 <b>왜 fail-closed 인가</b> — 답을 여는 것은 나중에 쉽지만 이미 보낸 답을
	 * 되돌리는 방법은 없다. 스레드형 후속 답변은 MB-09(추가 질문 횟수)와 함께 정해야 한다.</p>
	 */
	public static final Set<String> ANSWERABLE = Set.of(SUBMITTED, REVIEWING);

	private ConsultationStatus() {
	}
}
