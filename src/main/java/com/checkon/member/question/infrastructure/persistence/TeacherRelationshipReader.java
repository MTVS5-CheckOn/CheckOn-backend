package com.checkon.member.question.infrastructure.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 강사↔학생 관계 확인 (질문 등록 · 프로필 등에서 재사용).
 *
 * <p>🔴 학생 컨텍스트에서 부른다 — {@code teacher_student_relationships_member_student_select}
 * (V38:125-130)가 학생 self 로 격리한다. 남의 학생 관계는 자동으로 0행이다.</p>
 */
@Repository
public class TeacherRelationshipReader {

	private static final String EXISTS_ACTIVE_OR_PAUSED = """
		SELECT COUNT(*) > 0 FROM teacher_student_relationships
		WHERE teacher_id = ? AND student_id = ?
		  AND status IN ('ACTIVE', 'PAUSED')
		""";

	private final JdbcTemplate jdbcTemplate;

	public TeacherRelationshipReader(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean isTeachingStudent(UUID teacherId, UUID studentId) {
		Boolean exists = jdbcTemplate.queryForObject(
			EXISTS_ACTIVE_OR_PAUSED, Boolean.class, teacherId, studentId);
		return exists != null && exists;
	}
}
