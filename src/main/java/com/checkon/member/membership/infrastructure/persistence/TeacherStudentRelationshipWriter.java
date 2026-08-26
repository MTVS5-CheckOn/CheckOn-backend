package com.checkon.member.membership.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.common.persistence.RelationshipStatus;

/**
 * 강사↔학생 관계 생성·조회.
 *
 * <p>정책은 {@code teacher_student_relationships_member_student_insert}(V38:141-146) 이며
 * {@code current_checkon_student_id()} 를 요구한다 — 학생 본인만 자기 관계를 만든다.</p>
 */
@Repository
public class TeacherStudentRelationshipWriter {

	/**
	 * 🔴 부분 유니크 <b>인덱스</b>라 {@code ON CONSTRAINT} 로는 지목할 수 없다 — 인덱스 추론에
	 * 같은 술어를 그대로 적는다. 지목한 충돌만 무시하고 다른 위반은 그대로 터진다.
	 * (예외를 catch 하면 트랜잭션이 abort 돼 이후 문장이 전부 거절된다.)
	 */
	private static final String INSERT = """
		INSERT INTO teacher_student_relationships
		    (id, teacher_id, student_id, status, started_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT (teacher_id, student_id) WHERE status IN ('ACTIVE', 'PAUSED') DO NOTHING
		""";

	private final JdbcTemplate jdbcTemplate;

	public TeacherStudentRelationshipWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** @return 새 관계를 만들었으면 {@code true}, 이미 있어서 무시됐으면 {@code false} */
	public boolean insertActiveIfAbsent(UUID teacherId, UUID studentProfileId, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		return jdbcTemplate.update(INSERT, UUID.randomUUID(), teacherId, studentProfileId,
			RelationshipStatus.ACTIVE.name(), at, at) == 1;
	}

}
