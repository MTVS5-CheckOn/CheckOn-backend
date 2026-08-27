package com.checkon.member.learning.application;

import com.checkon.member.learning.domain.MemberAttemptStatus;

/**
 * 계약의 학습지 목록/상세 상태값. attempt 유무·상태에서 결정론으로 파생한다.
 *
 * <p>규칙 (분기표 §1 · 계약 {@code WorksheetSummary.status}):
 * <ul>
 *   <li>attempt 없음 → {@code NEW}</li>
 *   <li>{@code IN_PROGRESS} attempt 존재 → {@code IN_PROGRESS}</li>
 *   <li>{@code SUBMITTED}/{@code SCORED} attempt 존재 → {@code COMPLETED}</li>
 * </ul>
 *
 * <p>🔴 {@code SUBMITTED} 는 채점 중 크래시 잔여로만 나타난다(설계 §4 · 지시서 §4). 목록에서는
 * {@code COMPLETED} 로 표시하고, 실제 결과 조회는 attempt 경로가 {@code 409} 로 안내한다.</p>
 */
public final class WorksheetStatuses {

	public static final String NEW = "NEW";
	public static final String IN_PROGRESS = "IN_PROGRESS";
	public static final String COMPLETED = "COMPLETED";

	private WorksheetStatuses() {
	}

	public static String deriveFrom(MemberAttemptStatus attemptStatus) {
		if (attemptStatus == null) {
			return NEW;
		}
		return switch (attemptStatus) {
			case IN_PROGRESS -> IN_PROGRESS;
			case SUBMITTED, SCORED -> COMPLETED;
		};
	}
}
