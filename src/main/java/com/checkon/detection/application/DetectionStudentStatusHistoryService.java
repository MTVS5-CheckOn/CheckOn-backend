package com.checkon.detection.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DetectionStudentStatusHistoryService {

	private final JdbcTemplate jdbcTemplate;

	public DetectionStudentStatusHistoryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void record(
		UUID teacherId,
		UUID studentId,
		Instant occurredAt,
		String fromStatus,
		String toStatus
	) {
		Timestamp timestamp = Timestamp.from(occurredAt);
		jdbcTemplate.update("""
			INSERT INTO detection_student_status_history (
			    teacher_id, student_id, occurred_at, from_status, to_status, created_at
			) VALUES (?, ?, ?, ?, ?, ?)
			""", teacherId, studentId, timestamp, fromStatus, toStatus, timestamp);
	}

	public List<ReturnedTransition> findReturnedTransitions(
		UUID teacherId,
		Instant fromInclusive,
		Instant toExclusive
	) {
		return jdbcTemplate.query("""
			SELECT id, student_id, occurred_at, from_status, to_status
			FROM detection_student_status_history
			WHERE teacher_id = ?
			  AND to_status = 'returned'
			  AND occurred_at >= ?
			  AND occurred_at < ?
			ORDER BY occurred_at, id
			""", (resultSet, rowNumber) -> new ReturnedTransition(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("student_id", UUID.class),
			resultSet.getTimestamp("occurred_at").toInstant(),
			resultSet.getString("from_status"),
			resultSet.getString("to_status")
		), teacherId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
	}

	public record ReturnedTransition(
		UUID id,
		UUID studentId,
		Instant occurredAt,
		String fromStatus,
		String toStatus
	) {
	}
}
