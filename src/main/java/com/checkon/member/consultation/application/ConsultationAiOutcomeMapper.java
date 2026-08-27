package com.checkon.member.consultation.application;

import com.checkon.member.consultation.domain.ConsultationAiStatus;

/** 알 수 없는 adapter 결과를 예외로 전파하지 않고 UNAVAILABLE로 닫는다. */
public final class ConsultationAiOutcomeMapper {

	private ConsultationAiOutcomeMapper() {
	}

	public static ConsultationAiStatus map(String outcome) {
		if (outcome == null) {
			return ConsultationAiStatus.UNAVAILABLE;
		}
		return switch (outcome) {
			case "generated" -> ConsultationAiStatus.READY;
			case "template_only" -> ConsultationAiStatus.TEMPLATE_ONLY;
			case "rejected_insufficient" -> ConsultationAiStatus.REJECTED_INSUFFICIENT;
			default -> ConsultationAiStatus.UNAVAILABLE;
		};
	}
}
