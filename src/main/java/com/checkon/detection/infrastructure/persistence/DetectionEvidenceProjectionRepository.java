package com.checkon.detection.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads backend-owned projections that form absence and return evidence.
 *
 * <p>The caller sets the transaction-local teacher context before calling this
 * repository, so PostgreSQL RLS is the final tenant boundary in addition to
 * the explicit teacher_id predicate.</p>
 */
@Repository
public class DetectionEvidenceProjectionRepository {

	private final JdbcTemplate jdbcTemplate;

	public DetectionEvidenceProjectionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<AssignmentWeekSummary> findAssignmentSummaries(
		UUID teacherId,
		LocalDate fromWeekStart,
		LocalDate throughWeekStart
	) {
		return jdbcTemplate.query("""
			SELECT student_id, week_start, expected_count, submitted_count
			FROM detection_assignment_week_summaries
			WHERE teacher_id = ?
			  AND week_start >= ?
			  AND week_start <= ?
			ORDER BY student_id, week_start
			""", (resultSet, rowNumber) -> new AssignmentWeekSummary(
				resultSet.getObject("student_id", UUID.class),
				resultSet.getObject("week_start", LocalDate.class),
				resultSet.getInt("expected_count"),
				resultSet.getInt("submitted_count")
			), teacherId, fromWeekStart, throughWeekStart);
	}

	public List<StudentStatusTransition> findTransitions(
		UUID teacherId,
		Instant fromInclusive,
		Instant toExclusive
	) {
		return jdbcTemplate.query("""
			SELECT id, student_id, occurred_at, from_status, to_status
			FROM detection_student_status_history
			WHERE teacher_id = ?
			  AND occurred_at >= ?
			  AND occurred_at < ?
			ORDER BY occurred_at, id
			""", (resultSet, rowNumber) -> new StudentStatusTransition(
				resultSet.getObject("id", UUID.class),
				resultSet.getObject("student_id", UUID.class),
				resultSet.getObject("occurred_at", java.time.OffsetDateTime.class)
					.toInstant(),
				resultSet.getString("from_status"),
				resultSet.getString("to_status")
			), teacherId,
			fromInclusive.atOffset(ZoneOffset.UTC), toExclusive.atOffset(ZoneOffset.UTC));
	}

	public record AssignmentWeekSummary(
		UUID studentId,
		LocalDate weekStart,
		int expectedCount,
		int submittedCount
	) {
	}

	public record StudentStatusTransition(
		UUID id,
		UUID studentId,
		Instant occurredAt,
		String fromStatus,
		String toStatus
	) {
	}
}
