package com.checkon.member.learning.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberAttemptItemRow;

/**
 * {@code member_attempt_items} 저장·조회. 🔴 <b>UPDATE 정책이 없어 정책 레벨에서 동결</b>이다
 * (V40:250-253). 이 리포지토리도 UPDATE 를 제공하지 않는다 — 시작 시점 한 번 INSERT 로 끝난다.
 *
 * <p>정책: 학생 self select/insert (EXISTS 로 attempt 소유 확인), 강사 scope select
 * (V40:255-289). 학생 컨텍스트에서 자기 attempt 의 문항을 INSERT/SELECT 한다.</p>
 */
@Repository
public class MemberAttemptItemRepository {

	private static final String INSERT = """
		INSERT INTO member_attempt_items
			(attempt_id, item_id, ordinal, stem, passage, options, correct_no,
			 explanation, area_tag, type_tag, skill_node_id)
		VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
		""";

	private static final String FIND_BY_ATTEMPT = """
		SELECT attempt_id, item_id, ordinal, stem, passage, options::text, correct_no,
		       explanation, area_tag, type_tag, skill_node_id
		FROM member_attempt_items
		WHERE attempt_id = ?
		ORDER BY ordinal
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberAttemptItemRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 🔴 한 attempt 의 문항 전량을 한 배치로 넣는다. RLS 는 배치 안 각 행에 대해 걸린다. */
	public void insertAll(List<MemberAttemptItemRow> rows) {
		if (rows.isEmpty()) {
			return;
		}
		jdbcTemplate.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
				MemberAttemptItemRow row = rows.get(i);
				ps.setObject(1, row.attemptId());
				ps.setObject(2, row.itemId());
				ps.setInt(3, row.ordinal());
				ps.setString(4, row.stem());
				ps.setString(5, row.passage());
				ps.setObject(6, row.optionsJson());
				ps.setInt(7, row.correctNo());
				ps.setString(8, row.explanation());
				ps.setString(9, row.areaTag());
				ps.setString(10, row.typeTag());
				ps.setString(11, row.skillNodeId());
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	public List<MemberAttemptItemRow> findByAttempt(UUID attemptId) {
		return jdbcTemplate.query(FIND_BY_ATTEMPT, (rs, rowNum) -> new MemberAttemptItemRow(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getInt(3),
			rs.getString(4),
			rs.getString(5),
			rs.getString(6),
			rs.getInt(7),
			rs.getString(8),
			rs.getString(9),
			rs.getString(10),
			rs.getString(11)
		), attemptId);
	}

}
