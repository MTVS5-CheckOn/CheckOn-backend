package com.checkon.member.membership.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.common.persistence.RelationshipStatus;

/** 학부모↔강사 관계 생성·조회. 정책은 {@code parent_teacher_relationships_member_parent_*}(V38). */
@Repository
public class ParentTeacherRelationshipWriter {

	/** 🔴 부분 유니크 인덱스라 술어까지 적어 그 충돌만 무시한다. 위 writer 와 같은 이유다. */
	private static final String INSERT = """
		INSERT INTO parent_teacher_relationships
		    (id, parent_id, teacher_id, status, started_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT (parent_id, teacher_id) WHERE status = 'ACTIVE' DO NOTHING
		""";

	private final JdbcTemplate jdbcTemplate;

	public ParentTeacherRelationshipWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** @return 새 관계를 만들었으면 {@code true}, 이미 있어서 무시됐으면 {@code false} */
	public boolean insertActiveIfAbsent(UUID parentProfileId, UUID teacherId, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		return jdbcTemplate.update(INSERT, UUID.randomUUID(), parentProfileId, teacherId,
			RelationshipStatus.ACTIVE.name(), at, at) == 1;
	}
}
