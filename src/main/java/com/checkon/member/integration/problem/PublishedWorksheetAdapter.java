package com.checkon.member.integration.problem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 학생 화면이 볼 학습지 문항 스냅샷을 {@code saved_problem_set_items.item_snapshot}(V17) 에서
 * 읽어 member 전용 record 로 돌려준다.
 *
 * <p>🔴 <b>problem 패키지의 엔티티·리포지토리를 import 하지 않는다</b>(PR5 §3, G2 규칙).
 * 필요한 두 번째 매핑을 이 어댑터가 <b>네이티브 SQL 로</b> 만든다.</p>
 *
 * <p>🔴 <b>정답 번호를 역산하지 않는다</b>(2026-08-25 재측정 · PR5 §3). {@code correctNo}·
 * {@code areaTag}·{@code typeTag}·{@code skillNodeId} 는 스냅샷 JSONB 에 이미 들어 있으므로
 * 그대로 옮긴다. 문자열 매칭으로 번호를 찾던 옛 설계는 폐기다.</p>
 *
 * <p>🔴 <b>RLS 컨텍스트</b> — 이 조회는 학생 컨텍스트 위에 {@code checkon.scope_problem_set_id}
 * 가 세팅된 트랜잭션 안에서 돌아야 한다({@code saved_problem_set_items_member_student_select},
 * V38:176). 스코프가 없으면 예외가 아니라 <b>빈 결과</b>다(설계 §6-4-3). 스코프를 여는 것은
 * S3(attempt 시작 서비스)의 책임이다 — 이 어댑터는 열지 않는다.</p>
 */
@Component
public class PublishedWorksheetAdapter {

	private static final String FIND_BY_PROBLEM_SET_ID = """
		SELECT ordinal, item_snapshot::text
		FROM saved_problem_set_items
		WHERE problem_set_id = ?
		ORDER BY ordinal
		""";

	private static final String COUNT_BY_PROBLEM_SET_ID = """
		SELECT count(*) FROM saved_problem_set_items
		WHERE problem_set_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public PublishedWorksheetAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * 학습지 문항 수. 🔴 <b>{@code withVerifiedProblemSetScope} 안에서 부른다.</b> 스코프가
	 * 열리지 않았거나 학생이 소유자가 아니면 RLS 로 <b>0</b> 이 돌아온다 — 예외가 아니다
	 * (설계 §6-4-3). count 만 다뤄서 문항 본문을 읽을 필요가 없다.
	 */
	public int countItems(UUID problemSetId) {
		Integer count = jdbcTemplate.queryForObject(
			COUNT_BY_PROBLEM_SET_ID, Integer.class, problemSetId);
		return count == null ? 0 : count;
	}

	/**
	 * 스냅샷을 ordinal 오름차순으로 돌려준다. 스코프가 열리지 않았거나 학생이 소유자가 아니면
	 * RLS 로 <b>빈 리스트</b>가 돌아온다 — 호출자가 이 사실을 판정한다.
	 */
	public List<PublishedItemSnapshot> snapshotsOf(UUID problemSetId) {
		List<String> rows = jdbcTemplate.query(FIND_BY_PROBLEM_SET_ID,
			(rs, rowNum) -> rs.getString(2), problemSetId);
		List<PublishedItemSnapshot> snapshots = new ArrayList<>(rows.size());
		for (String json : rows) {
			snapshots.add(parse(json));
		}
		snapshots.sort(Comparator.comparingInt(PublishedItemSnapshot::ordinal));
		return List.copyOf(snapshots);
	}

	private PublishedItemSnapshot parse(String json) {
		JsonNode node;
		try {
			node = objectMapper.readTree(json);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException(
				"saved_problem_set_items.item_snapshot is not valid JSON", exception);
		}
		return new PublishedItemSnapshot(
			UUID.fromString(node.get("itemId").asString()),
			node.get("ordinal").asInt(),
			text(node, "stem"),
			text(node, "passage"),
			integer(node, "correctNo"),
			text(node, "explanation"),
			text(node, "areaTag"),
			text(node, "typeTag"),
			text(node, "skillNodeId"),
			parseOptions(node.get("options"))
		);
	}

	private List<PublishedItemOption> parseOptions(JsonNode options) {
		if (options == null || options.isNull() || !options.isArray()) {
			return List.of();
		}
		List<PublishedItemOption> parsed = new ArrayList<>(options.size());
		for (JsonNode option : options) {
			parsed.add(new PublishedItemOption(
				option.get("position").asInt(),
				text(option, "content"),
				text(option, "misconceptionTag")));
		}
		parsed.sort(Comparator.comparingInt(PublishedItemOption::position));
		return List.copyOf(parsed);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asInt();
	}
}
