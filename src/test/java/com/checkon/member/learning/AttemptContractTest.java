package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;
import com.checkon.member.learning.application.AttemptAnswerSnapshot;
import com.checkon.member.learning.application.AttemptInProgressResponse;
import com.checkon.member.learning.application.AttemptProjections;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PR5 지시서 §「검사」 신규 테스트 #1·#2. 🔴 <b>DTO 객체가 아니라 직렬화된 JSON 문자열</b>을 본다 —
 * 계약은 필드가 아니라 wire 다. {@code @JsonInclude(NON_NULL)} 로 숨긴 필드는 값이 non-null 인
 * 순간 그대로 새어나가므로, 실제 JSON 을 정답·해설이 non-null 인 픽스처로 만들어 확인한다.
 *
 * <p>🔴 이 테스트는 스프링 컨텍스트에 기대지 않는다. 컨트롤러가 없어도 <b>수동으로
 * projection 을 만들어 ObjectMapper 로 직렬화</b>한다 — 컨트롤러가 붙는 커밋 이전이라도
 * 계약이 지켜지는지를 즉시 실측한다.</p>
 */
class AttemptContractTest {

	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

	@Test
	@DisplayName("#1 IN_PROGRESS 응답 raw JSON 에 정답·해설·정오 키가 존재하지 않는다")
	void inProgressJsonHasNoAnswerKeys() throws Exception {
		AttemptInProgressResponse response = buildResponseWithNonNullAnswerData();
		String json = MAPPER.writeValueAsString(response);

		assertThat(json)
			.as("JSON 에 정답·해설·정오 키가 새어나가면 안 된다")
			.doesNotContain("correctNo")
			.doesNotContain("explanation")
			.doesNotContain("\"correct\"");
	}

	@Test
	@DisplayName("#2 픽스처의 정답·해설은 모두 non-null 이다 (테스트 1이 우연히 통과하지 않게)")
	void fixtureHasNonNullAnswers() {
		List<PublishedItemSnapshot> snapshots = fixtureSnapshots();
		assertThat(snapshots).allSatisfy(snapshot -> {
			assertThat(snapshot.correctNo())
				.as("correctNo 가 null 이면 테스트 #1 이 우연히 통과한다")
				.isNotNull();
			assertThat(snapshot.explanation())
				.as("explanation 이 null 이면 테스트 #1 이 우연히 통과한다")
				.isNotNull();
		});
	}

	private static AttemptInProgressResponse buildResponseWithNonNullAnswerData() {
		List<PublishedItemSnapshot> snapshots = fixtureSnapshots();
		return AttemptProjections.toInProgress(
			UUID.randomUUID(), UUID.randomUUID(), "IN_PROGRESS",
			0, snapshots.size(), 0,
			Instant.parse("2026-08-26T00:00:00Z"), null,
			snapshots,
			List.of(new AttemptAnswerSnapshot(snapshots.get(0).itemId(), null, 0, 0)));
	}

	private static List<PublishedItemSnapshot> fixtureSnapshots() {
		return List.of(
			new PublishedItemSnapshot(
				UUID.randomUUID(), 1, "본문 A", "지문",
				2, "해설 A", "reading", "fact", "S-01",
				List.of(new PublishedItemOption(1, "가", null),
					new PublishedItemOption(2, "나", "MC-01"))));
	}
}
