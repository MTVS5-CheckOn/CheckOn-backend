package com.checkon.member.learning.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.learning.application.dto.AttemptProgressRequest;

/**
 * progress 자동저장 요청의 검증. 코드 규칙 §7-a 로 서비스가 400 을 넘지 않게 이 클래스로 분리.
 *
 * <p>🔴 <b>상한을 조용히 clamp 하지 않는다</b> — 분기표 §1 progress 「시간 이상치」 대로 400 이다.
 * clamp 하면 학생의 실제 체류시간이 조용히 왜곡된다.</p>
 */
public final class AttemptProgressValidator {

	private final int maxDeltaSeconds;

	public AttemptProgressValidator(int maxDeltaSeconds) {
		this.maxDeltaSeconds = maxDeltaSeconds;
	}

	/** {@code baseVersion} · {@code clientSequence} 는 계약상 필수. 누락은 400. */
	public void validateHeader(AttemptProgressRequest request) {
		List<FieldViolation> violations = new ArrayList<>();
		if (request.baseVersion() == null) {
			violations.add(new FieldViolation("baseVersion", "must not be null"));
		}
		if (request.clientSequence() == null) {
			violations.add(new FieldViolation("clientSequence", "must not be null"));
		}
		if (!violations.isEmpty()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"progress request is missing required fields", violations);
		}
	}

	/**
	 * body 부분 검증. 미확인 itemId 가 있으면 {@link UnknownItemDetails} 로 400 을 낸다 —
	 * details 스키마가 다른 위반과 다르므로 별도 예외 경로다(분기표 「없는 itemId」).
	 */
	public void validateBody(
		Map<UUID, Integer> answers,
		Map<UUID, Integer> deltas,
		List<UUID> validItemIds,
		Map<UUID, Integer> optionCounts
	) {
		List<FieldViolation> violations = new ArrayList<>();
		List<UUID> unknownIds = new ArrayList<>();
		for (Map.Entry<UUID, Integer> entry : answers.entrySet()) {
			if (!validItemIds.contains(entry.getKey())) {
				unknownIds.add(entry.getKey());
				continue;
			}
			int selected = entry.getValue() == null ? 0 : entry.getValue();
			int optionCount = optionCounts.getOrDefault(entry.getKey(), 0);
			if (selected < 1 || selected > optionCount) {
				violations.add(new FieldViolation(
					"answers." + entry.getKey(),
					"must be in 1.." + optionCount));
			}
		}
		for (Map.Entry<UUID, Integer> entry : deltas.entrySet()) {
			if (!validItemIds.contains(entry.getKey())) {
				unknownIds.add(entry.getKey());
				continue;
			}
			int delta = entry.getValue() == null ? 0 : entry.getValue();
			if (delta < 0) {
				violations.add(new FieldViolation(
					"activeElapsedSecondsDelta." + entry.getKey(),
					"must not be negative"));
			}
			else if (delta > maxDeltaSeconds) {
				// 🔴 clamp 아님. 계약 상한 초과는 400.
				violations.add(new FieldViolation(
					"activeElapsedSecondsDelta." + entry.getKey(),
					"must not exceed " + maxDeltaSeconds));
			}
		}
		if (!unknownIds.isEmpty()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"unknown item ids", new UnknownItemDetails(List.copyOf(unknownIds)));
		}
		if (!violations.isEmpty()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"progress payload validation failed", violations);
		}
	}

	/** {@code details} 필드에 실릴 미확인 itemId 목록. */
	public record UnknownItemDetails(List<UUID> itemIds) {
	}
}
