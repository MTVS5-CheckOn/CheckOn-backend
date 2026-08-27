package com.checkon.member.membership.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.common.persistence.RelationshipStatus;

/**
 * 학부모↔자녀 관계 생성.
 *
 * <p>정책은 {@code parent_student_relationships_member_parent_insert}(V38:115-120) 이며
 * {@code current_checkon_parent_id()} 와 {@code status='ACTIVE'} 를 요구한다.</p>
 */
@Repository
public class ParentStudentRelationshipWriter {

	private static final String INSERT = """
		INSERT INTO parent_student_relationships
		    (id, parent_id, student_id, status, started_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
		""";

	private final JdbcTemplate jdbcTemplate;

	public ParentStudentRelationshipWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @return 새로 만든 관계 행의 id. 호출자가 알림 발행의 {@code sourceId} 로 쓴다 —
	 *         같은 관계는 unique key 로 재발행이 무시된다.
	 */
	public UUID insertActive(UUID parentProfileId, UUID studentProfileId, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(INSERT, id, parentProfileId, studentProfileId,
			RelationshipStatus.ACTIVE.name(), at, at);
		return id;
	}
}
