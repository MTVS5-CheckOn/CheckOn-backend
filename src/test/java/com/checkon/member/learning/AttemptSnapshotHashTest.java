package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;
import com.checkon.member.learning.application.AttemptSnapshotHash;

/**
 * PR5 지시서 §「검사」 신규 테스트 #16. 같은 입력 → 같은 해시, {@code null} 과 {@code ""} 는
 * <b>다른</b> 해시. 이 두 단언이 흔들리면 스냅샷 무결성 증명 자체가 흔들린다.
 */
class AttemptSnapshotHashTest {

	private static final UUID ITEM_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	@DisplayName("같은 입력을 두 번 넣으면 바이트 동일한 해시가 나온다")
	void isByteStable() {
		List<PublishedItemSnapshot> items = List.of(sample("본문", "지문", 2));
		String first = AttemptSnapshotHash.compute(items);
		String second = AttemptSnapshotHash.compute(items);
		assertThat(first).isEqualTo(second);
		assertThat(first).matches("^sha256:[0-9a-f]{64}$");
	}

	@Test
	@DisplayName("passage=null 과 passage=\"\" 는 다른 해시를 낸다")
	void nullAndEmptyPassageDiffer() {
		String withNull = AttemptSnapshotHash.compute(
			List.of(sample("본문", null, 1)));
		String withEmpty = AttemptSnapshotHash.compute(
			List.of(sample("본문", "", 1)));
		assertThat(withNull).isNotEqualTo(withEmpty);
	}

	@Test
	@DisplayName("정렬은 ordinal 기준이라 리스트 순서가 뒤바뀌어도 같은 해시가 나온다")
	void orderingByOrdinalIsCanonical() {
		PublishedItemSnapshot first = sample(UUID.randomUUID(), 1, "A");
		PublishedItemSnapshot second = sample(UUID.randomUUID(), 2, "B");
		String forward = AttemptSnapshotHash.compute(List.of(first, second));
		String reversed = AttemptSnapshotHash.compute(List.of(second, first));
		assertThat(forward).isEqualTo(reversed);
	}

	private static PublishedItemSnapshot sample(String stem, String passage, int correctNo) {
		return new PublishedItemSnapshot(
			ITEM_ID, 1, stem, passage, correctNo, "해설",
			"reading", "fact", "S-01",
			List.of(new PublishedItemOption(1, "가", null),
				new PublishedItemOption(2, "나", "MC-01")));
	}

	private static PublishedItemSnapshot sample(UUID itemId, int ordinal, String stem) {
		return new PublishedItemSnapshot(
			itemId, ordinal, stem, null, 1, null, "reading", "fact", "S-01",
			List.of(new PublishedItemOption(1, "가", null),
				new PublishedItemOption(2, "나", "MC-01")));
	}
}
