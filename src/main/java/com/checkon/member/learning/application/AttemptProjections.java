package com.checkon.member.learning.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;

/**
 * 응답 조립을 한 곳에 모은다. 컨트롤러·서비스가 필드를 하나씩 채우면 절대 규칙 4를 어길
 * 자리가 여러 개 생긴다 — 여기서만 만든다.
 *
 * <p>🔴 파라미터 타입에 {@code correctNo} 가 들어와도 반환 record 에 담을 자리가 없다
 * (타입이 방어다). {@link AttemptInProgressItem} · {@link AttemptItemResult} 를 참조하라.</p>
 */
public final class AttemptProjections {

	private AttemptProjections() {
	}

	/**
	 * 진행 중 attempt 응답을 만든다. {@code correctNo}·{@code explanation}·{@code correct} 는
	 * 파라미터로 받지도 않는다 — 담을 record 필드가 없다.
	 */
	public static AttemptInProgressResponse toInProgress(
		UUID attemptId,
		UUID assignmentId,
		String status,
		int version,
		String snapshotHash,
		UUID currentItemId,
		int totalActiveElapsedSeconds,
		Instant startedAt,
		List<PublishedItemSnapshot> snapshots,
		List<AttemptAnswerSnapshot> answers
	) {
		List<AttemptInProgressItem> items = snapshots.stream()
			.sorted(Comparator.comparingInt(PublishedItemSnapshot::ordinal))
			.map(AttemptProjections::toInProgressItem)
			.toList();
		Map<UUID, Integer> selected = new LinkedHashMap<>();
		Map<UUID, Integer> elapsed = new LinkedHashMap<>();
		for (AttemptAnswerSnapshot answer : answers) {
			if (answer.selectedNo() != null) {
				selected.put(answer.itemId(), answer.selectedNo());
			}
			elapsed.put(answer.itemId(), answer.activeElapsedSec());
		}
		return new AttemptInProgressResponse(
			attemptId, assignmentId, status, version, snapshotHash, currentItemId,
			totalActiveElapsedSeconds, startedAt, items,
			Map.copyOf(selected), Map.copyOf(elapsed));
	}

	private static AttemptInProgressItem toInProgressItem(PublishedItemSnapshot snapshot) {
		List<AttemptOption> options = snapshot.options().stream()
			.sorted(Comparator.comparingInt(PublishedItemOption::position))
			.map(option -> new AttemptOption(option.position(), option.content()))
			.toList();
		return new AttemptInProgressItem(
			snapshot.itemId(), snapshot.ordinal(), snapshot.stem(), snapshot.passage(),
			snapshot.areaTag(), snapshot.typeTag(), options);
	}

	/**
	 * 채점 결과 응답을 만든다. 모든 문항에 {@code correctNo}·{@code explanation}·{@code correct}
	 * 를 담는다 — 정답 문항의 접힌 해설도 열려야 한다.
	 */
	public static AttemptResult toResult(
		UUID attemptId,
		UUID assignmentId,
		int itemCount,
		int correctCount,
		double accuracyRate,
		int totalActiveElapsedSeconds,
		Instant startedAt,
		Instant submittedAt,
		Instant scoredAt,
		UUID learningRecordId,
		List<PublishedItemSnapshot> snapshots,
		Map<UUID, Integer> selectedByItemId
	) {
		List<AttemptItemResult> items = snapshots.stream()
			.sorted(Comparator.comparingInt(PublishedItemSnapshot::ordinal))
			.map(snapshot -> toItemResult(snapshot, selectedByItemId.get(snapshot.itemId())))
			.toList();
		return new AttemptResult(
			attemptId, assignmentId, "SCORED", itemCount, correctCount, accuracyRate,
			totalActiveElapsedSeconds, startedAt, submittedAt, scoredAt, learningRecordId, items);
	}

	private static AttemptItemResult toItemResult(
		PublishedItemSnapshot snapshot, Integer selectedNo
	) {
		List<AttemptOption> options = snapshot.options().stream()
			.sorted(Comparator.comparingInt(PublishedItemOption::position))
			.map(option -> new AttemptOption(option.position(), option.content()))
			.toList();
		int correctNo = snapshot.correctNo() == null ? 0 : snapshot.correctNo();
		boolean correct = selectedNo != null && correctNo != 0 && selectedNo == correctNo;
		return new AttemptItemResult(
			snapshot.itemId(), snapshot.ordinal(), selectedNo, correctNo, correct,
			snapshot.stem(), snapshot.passage(), snapshot.explanation(),
			snapshot.areaTag(), snapshot.typeTag(), options);
	}

	/** 편의: 답안 리스트에서 문항→선택번호 매핑을 만든다. */
	public static Map<UUID, Integer> selectedByItemId(List<AttemptAnswerSnapshot> answers) {
		return answers.stream()
			.filter(answer -> answer.selectedNo() != null)
			.collect(Collectors.toMap(
				AttemptAnswerSnapshot::itemId, AttemptAnswerSnapshot::selectedNo));
	}
}
