package com.checkon.dashboard.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.dashboard.application.DashboardBriefing.Alert;
import com.checkon.dashboard.application.DashboardBriefing.Evidence;
import com.checkon.dashboard.application.DashboardBriefing.Ref;
import com.checkon.dashboard.application.DashboardBriefing.Todo;
import com.checkon.dashboard.application.DashboardBriefing.Reminder;
import com.checkon.dashboard.application.DashboardBriefing.LatestIntervention;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

@Service
public class DashboardBriefingService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	private final JdbcTemplate jdbcTemplate;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public DashboardBriefingService(
		JdbcTemplate jdbcTemplate,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public DashboardBriefing getBriefing(UUID teacherProfileId, LocalDate date) {
		if (date.isAfter(LocalDate.now(clock.withZone(SERVICE_ZONE)))) {
			throw new FutureBriefingDateException();
		}
		tenantContext.setCurrentTeacher(teacherProfileId);

		List<Alert> alerts = jdbcTemplate.query(
			"""
				SELECT alert.id AS alert_id,
				       alert.student_id,
				       personal.real_name AS student_name,
				       class_group.name AS class_name,
				       signal.rule_id,
				       signal.signal_type,
				       signal.display_label,
				       signal.rank,
				       signal.brief_text,
				       signal.fallback_used,
				       alert.status,
				       alert.created_at,
				       evidence.record_id,
				       evidence.summary
				FROM engagement_alerts alert
				JOIN detection_signal_results signal
				  ON signal.id = alert.detection_signal_result_id
				JOIN detection_runs run
				  ON run.id = signal.detection_run_id
				JOIN detection_result_evidence evidence
				  ON evidence.detection_signal_result_id = signal.id
				LEFT JOIN student_personal_information personal
				  ON personal.student_id = alert.student_id
				LEFT JOIN class_groups class_group
				  ON class_group.teacher_id = alert.teacher_id
				 AND signal.class_ref = 'cl_' || replace(class_group.id::text, '-', '')
				WHERE alert.teacher_id = ?
				  AND run.teacher_id = ?
				  AND run.analysis_date = ?
				ORDER BY signal.rank ASC, alert.id ASC,
				         evidence.created_at ASC, evidence.id ASC
				""",
				ps -> {
					ps.setObject(1, teacherProfileId);
					ps.setObject(2, teacherProfileId);
					ps.setObject(3, date);
				},
				this::extractAlerts
		);
		List<Todo> todos = jdbcTemplate.query("""
			SELECT todo.id, todo.kind, todo.text, todo.alert_id, todo.due_date,
			       todo.created_at, signal.display_label
			FROM alert_follow_up_todos todo
			JOIN engagement_alerts alert
			  ON alert.id = todo.alert_id AND alert.teacher_id = todo.teacher_id
			JOIN detection_signal_results signal
			  ON signal.id = alert.detection_signal_result_id
			WHERE todo.teacher_id = ? AND todo.status = 'OPEN' AND todo.due_date <= ?
			ORDER BY todo.due_date ASC, todo.created_at ASC, todo.id ASC
			""", (resultSet, rowNumber) -> new Todo(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("kind"),
			resultSet.getString("text"),
			resultSet.getString("display_label"),
			resultSet.getTimestamp("created_at").toInstant(),
			new Ref(resultSet.getObject("alert_id", UUID.class), "alert_detail"),
			resultSet.getObject("due_date", LocalDate.class), false
		), teacherProfileId, date);
		Instant endExclusive = date.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();
		List<Reminder> reminders = jdbcTemplate.query("""
			WITH candidates AS (
			  SELECT reminder.id AS reminder_id, intervention.alert_id,
			         intervention.student_id, intervention.id AS intervention_id,
			         personal.real_name AS student_name,
			         intervention.type, intervention.created_at,
			         reminder.scheduled_at,
			         COUNT(*) OVER (PARTITION BY intervention.alert_id) AS intervention_count,
			         ROW_NUMBER() OVER (
			           PARTITION BY intervention.alert_id
			           ORDER BY intervention.created_at DESC, intervention.id DESC
			         ) AS representative_rank
			  FROM interventions intervention
			  JOIN intervention_reminders reminder
			    ON reminder.intervention_id = intervention.id
			   AND reminder.teacher_id = intervention.teacher_id
			  LEFT JOIN student_personal_information personal
			    ON personal.student_id = intervention.student_id
			  WHERE intervention.teacher_id = ?
			    AND intervention.status = 'OPEN'
			    AND reminder.status = 'ACTIVE'
			    AND reminder.scheduled_at < ?
			)
			SELECT reminder_id, alert_id, student_id, student_name, intervention_count,
			       intervention_id, type, created_at, scheduled_at
			FROM candidates
			WHERE representative_rank = 1
			ORDER BY scheduled_at ASC, alert_id ASC
			""", (resultSet, rowNumber) -> new Reminder(
			resultSet.getObject("reminder_id", UUID.class),
			resultSet.getObject("alert_id", UUID.class),
			resultSet.getObject("student_id", UUID.class),
			resultSet.getString("student_name"),
			resultSet.getLong("intervention_count"),
			new LatestIntervention(
				resultSet.getObject("intervention_id", UUID.class),
				resultSet.getString("type"),
				"개입 후 재확인이 예정되어 있습니다.",
				resultSet.getTimestamp("created_at").toInstant()
			),
			resultSet.getTimestamp("scheduled_at").toInstant()
		), teacherProfileId, endExclusive.atOffset(java.time.ZoneOffset.UTC));
		return new DashboardBriefing(
			date, List.copyOf(alerts), List.copyOf(todos), List.copyOf(reminders)
		);
	}

	private List<Alert> extractAlerts(ResultSet resultSet) throws SQLException {
		var rows = new LinkedHashMap<UUID, AlertAccumulator>();
		while (resultSet.next()) {
			UUID alertId = resultSet.getObject("alert_id", UUID.class);
			AlertAccumulator alert = rows.computeIfAbsent(alertId, ignored -> new AlertAccumulator(
				alertId,
				resultSetUuid(resultSet, "student_id"),
				resultSetString(resultSet, "student_name"),
				resultSetString(resultSet, "class_name"),
				resultSetString(resultSet, "rule_id"),
				resultSetString(resultSet, "signal_type"),
				resultSetString(resultSet, "display_label"),
				resultSetInt(resultSet, "rank"),
				resultSetString(resultSet, "brief_text"),
				resultSetBoolean(resultSet, "fallback_used"),
				resultSetString(resultSet, "status"),
				resultSetInstant(resultSet, "created_at")
			));
			alert.evidence().add(new Evidence(
				resultSet.getString("record_id"), resultSet.getString("summary")
			));
		}
		return rows.values().stream().map(AlertAccumulator::toAlert).toList();
	}

	private UUID resultSetUuid(ResultSet rs, String column) {
		try { return rs.getObject(column, UUID.class); }
		catch (SQLException exception) { throw new IllegalStateException(exception); }
	}

	private String resultSetString(ResultSet rs, String column) {
		try { return rs.getString(column); }
		catch (SQLException exception) { throw new IllegalStateException(exception); }
	}

	private int resultSetInt(ResultSet rs, String column) {
		try { return rs.getInt(column); }
		catch (SQLException exception) { throw new IllegalStateException(exception); }
	}

	private boolean resultSetBoolean(ResultSet rs, String column) {
		try { return rs.getBoolean(column); }
		catch (SQLException exception) { throw new IllegalStateException(exception); }
	}

	private Instant resultSetInstant(ResultSet rs, String column) {
		try { return rs.getTimestamp(column).toInstant(); }
		catch (SQLException exception) { throw new IllegalStateException(exception); }
	}

	private record AlertAccumulator(
		UUID alertId, UUID studentId, String studentName, String className, String ruleId,
		String signalType, String displayLabel, int rank, String brief, boolean briefFallback,
		String status, Instant createdAt, List<Evidence> evidence
	) {
		AlertAccumulator(
			UUID alertId, UUID studentId, String studentName, String className, String ruleId,
			String signalType, String displayLabel, int rank, String brief, boolean briefFallback,
			String status, Instant createdAt
		) {
			this(alertId, studentId, studentName, className, ruleId, signalType, displayLabel,
				rank, brief, briefFallback, status, createdAt, new ArrayList<>());
		}

		Alert toAlert() {
			return new Alert(alertId, studentId, studentName, className, ruleId, signalType,
				displayLabel, rank, brief, briefFallback, status, createdAt, List.copyOf(evidence));
		}
	}
}
