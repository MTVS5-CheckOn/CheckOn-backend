package com.checkon.member.integration.problem;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 채점 결과를 승우님의 {@code problem_assignment_responses}(V34) 에 문항당 1행으로 기록한다.
 *
 * <p>🔴 <b>member 는 자기 결과 테이블을 만들지 않는다</b>(설계 정본 §1-4 ⑤ · V40:56-60).
 * 이 어댑터가 학생 컨텍스트에서 이 테이블에 INSERT 하며, PR2 의
 * {@code problem_assignment_responses_member_student_insert}(V38:201-206) 가 격리한다.</p>
 *
 * <p>🔴 <b>CHECK 가 correct = (chosen_no = correct_no) 를 강제한다</b>(V34:265). 애플리케이션의
 * 계산이 어긋나면 DB 가 거절한다 — 이중 방어다.</p>
 *
 * <p>🔴 <b>uq_problem_response_item</b>(V34:261) — 같은 assignment·item 재제출은 23505 다.
 * 서비스가 제출 트랜잭션을 {@code FOR UPDATE} 로 직렬화하고 {@code status != IN_PROGRESS} 를
 * 먼저 판정하므로 이 위반은 정상 경로에서 나오지 않는다.</p>
 */
@Component
public class ProblemAssignmentResponseWriter {

	private static final String INSERT = """
		INSERT INTO problem_assignment_responses
			(id, teacher_id, assignment_id, student_id, problem_set_id, item_id,
			 chosen_no, correct_no, correct, area_tag, type_tag, skill_node_id,
			 misconception_tag, responded_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private final JdbcTemplate jdbcTemplate;

	public ProblemAssignmentResponseWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 문항 결과 전량을 한 배치로 넣는다. 🔴 <b>{@link ScoredResponseRow#chosenNo} 는 절대 null 이
	 * 될 수 없다</b> — {@code problem_assignment_responses.chosen_no} 가 NOT NULL 이다. 미응답은
	 * 이 배치에 포함하지 않는다(호출자가 걸러야 한다).
	 */
	public void insertAll(UUID assignmentId, UUID problemSetId, List<ScoredResponseRow> rows,
		Instant now) {
		if (rows.isEmpty()) {
			return;
		}
		OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
				ScoredResponseRow row = rows.get(i);
				ps.setObject(1, UUID.randomUUID());
				ps.setObject(2, row.teacherId());
				ps.setObject(3, assignmentId);
				ps.setObject(4, row.studentId());
				ps.setObject(5, problemSetId);
				ps.setObject(6, row.itemId());
				ps.setInt(7, row.chosenNo());
				ps.setInt(8, row.correctNo());
				ps.setBoolean(9, row.correct());
				ps.setString(10, row.areaTag());
				ps.setString(11, row.typeTag());
				ps.setString(12, row.skillNodeId());
				ps.setString(13, row.misconceptionTag());
				ps.setObject(14, timestamp);
				ps.setObject(15, timestamp);
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	/**
	 * 문항 결과 한 개의 값 객체. record 로 묶는 이유는 파라미터가 8개이기 때문 —
	 * 위치 인자로 넘기면 순서를 틀리기 쉬운데, 그 위에 CHECK 가 있어 틀리면 DB 가 삼킨다.
	 *
	 * <p>🔴 {@code misconceptionTag} 는 <b>정답이면 null · 오답이면 non-null</b>이어야 한다 —
	 * {@code ck_problem_response_misconception}(V34:274-279) 가 강제한다.</p>
	 */
	public record ScoredResponseRow(
		UUID teacherId,
		UUID studentId,
		UUID itemId,
		int chosenNo,
		int correctNo,
		boolean correct,
		String areaTag,
		String typeTag,
		String skillNodeId,
		String misconceptionTag
	) {
	}
}
