package com.checkon.member.learning.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberAttemptAnswer;

/**
 * {@code member_attempt_answers} 저장·조회. 학생 self select/insert/update 만 있고 학부모·강사
 * 정책은 없다(V40:291-328) — 진행 중 답안은 학생만 보고 쓴다.
 *
 * <p>{@link #insertPlaceholders} 는 attempt 시작 시 문항 수만큼 {@code selected_no=NULL} 로
 * 미리 넣는다(§4 3단계). progress 자동저장은 UPSERT 로 이어붙인다.</p>
 */
@Repository
public class MemberAttemptAnswerRepository {

	private static final String INSERT_PLACEHOLDER = """
		INSERT INTO member_attempt_answers
			(attempt_id, item_id, selected_no, active_elapsed_sec, revision, updated_at)
		VALUES (?, ?, NULL, 0, 0, ?)
		""";

	private static final String FIND_BY_ATTEMPT = """
		SELECT attempt_id, item_id, selected_no, active_elapsed_sec, revision, updated_at
		FROM member_attempt_answers
		WHERE attempt_id = ?
		ORDER BY item_id
		""";

	// 🔴 progress UPSERT — placeholder 가 이미 있어도 selected_no·active_elapsed_sec 를
	//    누적하며 revision 을 +1 한다. 항목당 delta 는 절대값 가산이 아니라 「덧셈」이라
	//    active_elapsed_sec 는 EXCLUDED 로 덮지 않고 + 로 더한다.
	private static final String UPSERT_ANSWER = """
		INSERT INTO member_attempt_answers
			(attempt_id, item_id, selected_no, active_elapsed_sec, revision, updated_at)
		VALUES (?, ?, ?, ?, 1, ?)
		ON CONFLICT (attempt_id, item_id) DO UPDATE
		SET selected_no = COALESCE(EXCLUDED.selected_no, member_attempt_answers.selected_no),
		    active_elapsed_sec = member_attempt_answers.active_elapsed_sec
		        + EXCLUDED.active_elapsed_sec,
		    revision = member_attempt_answers.revision + 1,
		    updated_at = EXCLUDED.updated_at
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberAttemptAnswerRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insertPlaceholders(UUID attemptId, List<UUID> itemIds, Instant now) {
		if (itemIds.isEmpty()) {
			return;
		}
		OffsetDateTime updatedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.batchUpdate(INSERT_PLACEHOLDER, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
				ps.setObject(1, attemptId);
				ps.setObject(2, itemIds.get(i));
				ps.setObject(3, updatedAt);
			}

			@Override
			public int getBatchSize() {
				return itemIds.size();
			}
		});
	}

	/**
	 * 항목 한 개의 답안 delta 를 반영한다. {@code selectedNo} 는 null 이면 기존 값을 유지하고,
	 * {@code activeElapsedDelta} 는 항상 누적된다. 호출자는 상한(600) 검증을 미리 통과시켰어야 한다.
	 */
	public void upsert(
		UUID attemptId,
		UUID itemId,
		Integer selectedNo,
		int activeElapsedDelta,
		Instant now
	) {
		OffsetDateTime updatedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(UPSERT_ANSWER,
			attemptId, itemId, selectedNo, activeElapsedDelta, updatedAt);
	}

	public List<MemberAttemptAnswer> findByAttempt(UUID attemptId) {
		return jdbcTemplate.query(FIND_BY_ATTEMPT, (rs, rowNum) -> new MemberAttemptAnswer(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			(Integer) rs.getObject(3),
			rs.getInt(4),
			rs.getInt(5),
			rs.getObject(6, OffsetDateTime.class).toInstant()
		), attemptId);
	}
}
