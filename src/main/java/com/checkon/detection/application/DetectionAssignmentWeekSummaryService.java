package com.checkon.detection.application;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DetectionAssignmentWeekSummaryService {

	private final JdbcTemplate jdbcTemplate;

	public DetectionAssignmentWeekSummaryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<AssignmentWeekSummary> findAll(
		UUID teacherId,
		LocalDate fromInclusive,
		LocalDate toInclusive
	) {
		return jdbcTemplate.query("""
			SELECT id, student_id, week_start, expected_count, submitted_count
			FROM detection_assignment_week_summaries
			WHERE teacher_id = ?
			  AND week_start >= ?
			  AND week_start <= ?
			ORDER BY week_start, student_id
			""", (resultSet, rowNumber) -> new AssignmentWeekSummary(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("student_id", UUID.class),
			resultSet.getObject("week_start", LocalDate.class),
			resultSet.getInt("expected_count"),
			resultSet.getInt("submitted_count")
		), teacherId, Date.valueOf(fromInclusive), Date.valueOf(toInclusive));
	}

	public record AssignmentWeekSummary(
		UUID id,
		UUID studentId,
		LocalDate weekStart,
		int expectedCount,
		int submittedCount
	) {
		public String recordId() {
			return id.toString();
		}
	}
}
