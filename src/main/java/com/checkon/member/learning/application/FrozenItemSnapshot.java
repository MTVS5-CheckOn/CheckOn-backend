package com.checkon.member.learning.application;

import java.util.ArrayList;
import java.util.List;

import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;
import com.checkon.member.learning.domain.MemberAttemptItemRow;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 시작 시점에 동결된 {@code member_attempt_items} 한 행을 {@link PublishedItemSnapshot} 으로
 * 되돌린다. 🔴 <b>이 변환은 GET/progress 경로에서만 쓴다</b> — 조회 경로는 절대로
 * {@code saved_problem_set_items} 를 다시 읽지 않는다(PR5 §4 · 「복사 이후 조회는 member_* 만」).
 *
 * <p>{@code options} 는 저장 시 JSON 문자열로 넣었고({@link MemberAttemptItemRow#optionsJson}),
 * 여기서 파싱해서 {@link PublishedItemOption} 리스트로 돌려준다. 파싱이 실패하면
 * {@link IllegalStateException} — 조용히 빈 리스트를 돌려주면 응답에서 보기가 사라진다.</p>
 */
public final class FrozenItemSnapshot {

	private FrozenItemSnapshot() {
	}

	public static PublishedItemSnapshot fromRow(MemberAttemptItemRow row, ObjectMapper mapper) {
		return new PublishedItemSnapshot(
			row.itemId(), row.ordinal(), row.stem(), row.passage(),
			row.correctNo(), row.explanation(),
			row.areaTag(), row.typeTag(), row.skillNodeId(),
			parseOptions(row.optionsJson(), mapper));
	}

	private static List<PublishedItemOption> parseOptions(String json, ObjectMapper mapper) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		JsonNode node;
		try {
			node = mapper.readTree(json);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException(
				"member_attempt_items.options is not valid JSON", exception);
		}
		if (!node.isArray()) {
			return List.of();
		}
		List<PublishedItemOption> options = new ArrayList<>(node.size());
		for (JsonNode option : node) {
			int position = option.get("position").asInt();
			String content = option.hasNonNull("content") ? option.get("content").asString() : null;
			String misconception = option.hasNonNull("misconceptionTag")
				? option.get("misconceptionTag").asString() : null;
			options.add(new PublishedItemOption(position, content, misconception));
		}
		return List.copyOf(options);
	}
}
