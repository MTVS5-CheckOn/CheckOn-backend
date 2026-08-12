package com.checkon.engagement.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;

/** Exposes tenant-scoped Alert lifecycle history to the Detection snapshot boundary. */
@Service
public class EngagementAlertContextService {

	private final JdbcTemplate jdbcTemplate;
	private final TeacherTenantDatabaseContext tenantContext;

	public EngagementAlertContextService(
		JdbcTemplate jdbcTemplate,
		TeacherTenantDatabaseContext tenantContext
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public List<AlertHistory> latestByStudentAndSignalType(UUID teacherId) {
		tenantContext.setCurrentTeacher(teacherId);
		return jdbcTemplate.query("""
			WITH history AS (
			  SELECT alert.id,
			         alert.student_id,
			         signal.signal_type,
			         CASE
			           WHEN alert.status = 'REJECTED' OR completed.completed_at IS NOT NULL
			             THEN 'resolved'
			           ELSE 'open'
			         END AS context_status,
			         CASE
			           WHEN alert.status = 'REJECTED' THEN alert.decided_at
			           ELSE completed.completed_at
			         END AS resolved_at,
			         completed.completed_at IS NOT NULL AS followed_up,
			         alert.created_at
			  FROM engagement_alerts alert
			  JOIN detection_signal_results signal
			    ON signal.id = alert.detection_signal_result_id
			  JOIN detection_runs run
			    ON run.id = signal.detection_run_id
			  LEFT JOIN LATERAL (
			    SELECT max(intervention.completed_at) AS completed_at
			    FROM interventions intervention
			    WHERE intervention.teacher_id = alert.teacher_id
			      AND intervention.alert_id = alert.id
			      AND intervention.status = 'COMPLETED'
			  ) completed ON true
			  WHERE alert.teacher_id = ? AND run.teacher_id = ?
			), ranked AS (
			  SELECT history.*,
			         row_number() OVER (
			           PARTITION BY student_id, signal_type
			           ORDER BY CASE WHEN context_status = 'open' THEN 0 ELSE 1 END,
			                    created_at DESC, id DESC
			         ) AS position
			  FROM history
			)
			SELECT student_id, signal_type, context_status, resolved_at, followed_up
			FROM ranked
			WHERE position = 1
			ORDER BY student_id, signal_type
			""", (resultSet, rowNumber) -> history(resultSet), teacherId, teacherId);
	}

	private AlertHistory history(ResultSet resultSet) throws SQLException {
		OffsetDateTime resolvedAt = resultSet.getObject("resolved_at", OffsetDateTime.class);
		return new AlertHistory(
			resultSet.getObject("student_id", UUID.class),
			resultSet.getString("signal_type"),
			resultSet.getString("context_status"),
			resolvedAt == null ? null : resolvedAt.toInstant(),
			resultSet.getBoolean("followed_up")
		);
	}

	public record AlertHistory(
		UUID studentId,
		String signalType,
		String status,
		Instant resolvedAt,
		boolean followedUp
	) {
	}
}
