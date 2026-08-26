package com.checkon.member.learning.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 결정론 MCQ 채점. 🔴 <b>실 LLM 호출 0회</b>(절대 규칙 7). 스냅샷의 {@code correctNo} 와
 * 학생이 고른 {@code selectedNo} 를 정수로 비교한다.
 *
 * <p>{@code selectedNo == null}(미응답)은 오답이 아니라 "채점 대상이 아닌 값" 이다 —
 * {@link #score} 는 {@code correctCount} 에서 제외한다. 미응답을 오답으로 처리하고 싶으면
 * 제출 정책({@code member.attempt.require-complete-submission}) 이 <b>미응답 자체를 거절</b>한다.</p>
 */
public final class AttemptScoring {

	private AttemptScoring() {
	}

	public static ScoreResult score(
		Map<UUID, Integer> correctByItemId,
		Map<UUID, Integer> selectedByItemId
	) {
		int total = correctByItemId.size();
		int correct = 0;
		for (Map.Entry<UUID, Integer> entry : correctByItemId.entrySet()) {
			Integer selected = selectedByItemId.get(entry.getKey());
			if (selected != null && selected.equals(entry.getValue())) {
				correct++;
			}
		}
		BigDecimal rate = total == 0 ? BigDecimal.ZERO :
			BigDecimal.valueOf(correct)
				.divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
		return new ScoreResult(total, correct, rate.doubleValue());
	}

	/**
	 * 문항 하나의 정오 판정. 미응답은 오답이 아니다 — {@code null} 을 돌려 「채점되지 않음」을 표현한다.
	 */
	public static Boolean grade(Integer correctNo, Integer selectedNo) {
		if (selectedNo == null) {
			return null;
		}
		return correctNo != null && correctNo.equals(selectedNo);
	}

	/** 문항 ID → 정답 번호 매핑을 스냅샷 리스트에서 만든다. 순수 편의 함수. */
	public static Map<UUID, Integer> correctByItemId(
		java.util.Collection<AttemptItemCorrectness> items,
		Function<AttemptItemCorrectness, UUID> itemIdOf,
		Function<AttemptItemCorrectness, Integer> correctNoOf
	) {
		return items.stream().collect(Collectors.toMap(itemIdOf, correctNoOf));
	}

	public record ScoreResult(int total, int correct, double accuracyRate) {
	}

	/**
	 * 채점에 필요한 최소 항목. 인터페이스 대신 record 를 쓰는 이유는 채점 함수를 순수 데이터에만
	 * 의존하게 만들기 위해서다 — 도메인 엔티티를 파라미터로 받으면 지연 로딩이 여기로 새어 들어온다.
	 */
	public record AttemptItemCorrectness(UUID itemId, int correctNo) {
	}
}
