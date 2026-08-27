package com.checkon.member.consultation.infrastructure.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 학부모 자녀 scope 안에서 학생-강사 관계를 재검증한다. */
@Repository
public class ConsultationRelationshipRepository {

	private static final String ACTIVE_TEACHER = """
		SELECT count(*) FROM teacher_student_relationships
		WHERE teacher_id = ? AND student_id = ? AND status IN ('ACTIVE', 'PAUSED')
		""";

	private final JdbcTemplate jdbcTemplate;

	public ConsultationRelationshipRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean isTeacherForStudent(UUID teacherId, UUID studentId) {
		Integer count = jdbcTemplate.queryForObject(
			ACTIVE_TEACHER, Integer.class, teacherId, studentId);
		return count != null && count > 0;
	}
}
