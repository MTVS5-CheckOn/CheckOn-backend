package com.checkon.counsel.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest.Direction;

@Repository
public class GuardianCommunicationHistoryRepository {

	private final JdbcTemplate jdbcTemplate;

	public GuardianCommunicationHistoryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean hasActiveTeacherRelationship(UUID teacherId, UUID parentId) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT count(*) FROM parent_teacher_relationships
			WHERE teacher_id = ? AND parent_id = ? AND status = 'ACTIVE'
			""", Integer.class, teacherId, parentId);
		return count != null && count > 0;
	}

	public List<String> findLinkedStudentRealNames(UUID teacherId, UUID parentId) {
		return jdbcTemplate.query("""
			SELECT personal.real_name
			FROM parent_student_relationships parent_student
			JOIN teacher_student_relationships teacher_student
			  ON teacher_student.student_id = parent_student.student_id
			 AND teacher_student.teacher_id = ?
			 AND teacher_student.status = 'ACTIVE'
			JOIN student_personal_information personal
			  ON personal.student_id = parent_student.student_id
			WHERE parent_student.parent_id = ? AND parent_student.status = 'ACTIVE'
			""", (rs, row) -> rs.getString(1), teacherId, parentId);
	}

	public List<HistoryEntry> findLatest(UUID teacherId, UUID parentId, int limit) {
		return jdbcTemplate.query("""
			WITH parent_students AS (
			    SELECT parent_student.student_id
			    FROM parent_student_relationships parent_student
			    JOIN teacher_student_relationships teacher_student
			      ON teacher_student.student_id = parent_student.student_id
			     AND teacher_student.teacher_id = ?
			     AND teacher_student.status = 'ACTIVE'
			    WHERE parent_student.parent_id = ? AND parent_student.status = 'ACTIVE'
			), communication AS (
			    SELECT 'inquiry:' || inquiry.inquiry_ref AS record_id,
			           'inbound' AS direction, inquiry.raw_text AS body, inquiry.received_at AS occurred_at
			    FROM counsel_inquiries inquiry
			    JOIN parent_students ON parent_students.student_id = inquiry.student_id
			    WHERE inquiry.teacher_id = ?
			    UNION ALL
			    SELECT 'sent:' || job.job_id AS record_id,
			           'outbound' AS direction, job.sent_text AS body, job.sent_at AS occurred_at
			    FROM counsel_draft_jobs job
			    JOIN counsel_inquiries inquiry
			      ON inquiry.teacher_id = job.teacher_id AND inquiry.inquiry_ref = job.inquiry_ref
			    JOIN parent_students ON parent_students.student_id = inquiry.student_id
			    WHERE job.teacher_id = ? AND job.sent_text IS NOT NULL AND job.sent_at IS NOT NULL
			), latest AS (
			    SELECT * FROM communication ORDER BY occurred_at DESC, record_id DESC LIMIT ?
			)
			SELECT record_id, direction, body, occurred_at
			FROM latest ORDER BY occurred_at, record_id
			""", (rs, row) -> new HistoryEntry(
			rs.getString("record_id"),
			Direction.valueOf(rs.getString("direction")),
			rs.getString("body"),
			rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()
		), teacherId, parentId, teacherId, teacherId, limit);
	}

	public record HistoryEntry(String recordId, Direction direction, String text, Instant at) {
	}
}
