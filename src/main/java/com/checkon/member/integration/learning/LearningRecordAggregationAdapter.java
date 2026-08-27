package com.checkon.member.integration.learning;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 월 집계의 원천 — {@code problem_assignment_responses}(V33) 를 문항 단위로 읽는다.
 *
 * <p>🔴 <b>{@code learning_records} 를 쓰지 않는다</b>(2026-08-25 재측정 · 지시서 §180). 그 테이블의
 * {@code area_tag}·{@code type_tag} 는 「타깃 1개일 때만 상속」 우회로 채워져 있어 실제 보유율이
 * 낮다. 여기 원천은 문항 단위로 항상 태그를 가지고 있다(NOT NULL 정규식 CHECK).</p>
 *
 * <p>🔴 정책은 <b>학생 self SELECT</b>(V38: {@code problem_assignment_responses_member_student_select})
 * 로 격리한다. 학생 컨텍스트가 열려 있어야 이 어댑터가 결과를 낸다 — 밖에서 부르면 <b>0행</b>이다.</p>
 *
 * <p>🔴 {@code com.checkon.problem} 을 <b>import 하지 않는다</b> — SQL 규칙만 참고한다(PR5 무접촉 규칙).</p>
 */
@Component
public class LearningRecordAggregationAdapter {

	/**
	 * 셀 = (teacher_id, area_tag, type_tag). area/type 은 소문자 정본으로 그대로 저장돼 있다
	 * (`ck_problem_response_area`·`ck_problem_response_type`). 대소문자 변환은 하지 않는다.
	 *
	 * <p>🔴 {@code item_format='mcq'} 필터는 <b>넣지 않는다</b> — 이 테이블은 MCQ 전용이다
	 * ({@code chosen_no BETWEEN 1 AND 5}).</p>
	 */
	private static final String SELECT_CELLS = """
		SELECT teacher_id, area_tag, type_tag,
		       count(*)::int AS scored_count,
		       count(*) FILTER (WHERE correct)::int AS correct_count
		FROM problem_assignment_responses
		WHERE student_id = ?
		  AND responded_at >= ? AND responded_at < ?
		GROUP BY teacher_id, area_tag, type_tag
		""";

	private final JdbcTemplate jdbcTemplate;

	public LearningRecordAggregationAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 학생 컨텍스트가 열려 있을 때만 결과를 낸다. 셀 하나당 1행(교사가 다르면 별도 행).
	 *
	 * @param studentId 학생 프로필 id
	 * @param from      포함 (UTC Instant)
	 * @param to        미포함 (UTC Instant)
	 */
	public List<AggregatedCell> aggregate(UUID studentId, Instant from, Instant to) {
		OffsetDateTime fromOff = OffsetDateTime.ofInstant(from, ZoneOffset.UTC);
		OffsetDateTime toOff = OffsetDateTime.ofInstant(to, ZoneOffset.UTC);
		List<AggregatedCell> cells = new ArrayList<>();
		jdbcTemplate.query(SELECT_CELLS, rs -> {
			cells.add(new AggregatedCell(
				rs.getObject("teacher_id", UUID.class),
				rs.getString("area_tag"),
				rs.getString("type_tag"),
				rs.getInt("scored_count"),
				rs.getInt("correct_count")
			));
		}, studentId, fromOff, toOff);
		return cells;
	}

	/**
	 * 한 셀. area/type 은 소문자 정본으로 그대로 온다({@code problem_assignment_responses} CHECK).
	 */
	public record AggregatedCell(
		UUID teacherId,
		String areaTag,
		String typeTag,
		int scoredCount,
		int correctCount
	) {
	}
}
