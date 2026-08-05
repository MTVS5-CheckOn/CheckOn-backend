package com.checkon.dashboard.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
				       signal.rule_id,
				       signal.signal_type,
				       signal.rank,
				       signal.brief_text,
				       signal.fallback_used,
				       alert.status,
				       evidence.record_id,
				       evidence.summary
				FROM engagement_alerts alert
				JOIN detection_signal_results signal
				  ON signal.id = alert.detection_signal_result_id
				JOIN detection_runs run
				  ON run.id = signal.detection_run_id
				JOIN detection_result_evidence evidence
				  ON evidence.detection_signal_result_id = signal.id
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
			SELECT id, kind, text, alert_id, due_date
			FROM alert_follow_up_todos
			WHERE teacher_id = ? AND status = 'OPEN' AND due_date <= ?
			ORDER BY due_date ASC, created_at ASC, id ASC
			""", (resultSet, rowNumber) -> new Todo(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("kind"),
			resultSet.getString("text"),
			new Ref(resultSet.getObject("alert_id", UUID.class), "alert_detail"),
			resultSet.getObject("due_date", LocalDate.class), false
		), teacherProfileId, date);
		return new DashboardBriefing(date, List.copyOf(alerts), List.copyOf(todos));
	}

	private List<Alert> extractAlerts(ResultSet resultSet) throws SQLException {
		var rows = new LinkedHashMap<UUID, AlertAccumulator>();
		while (resultSet.next()) {
			UUID alertId = resultSet.getObject("alert_id", UUID.class);
			AlertAccumulator alert = rows.computeIfAbsent(alertId, ignored -> new AlertAccumulator(
				alertId,
				resultSetUuid(resultSet, "student_id"),
				resultSetString(resultSet, "rule_id"),
				resultSetString(resultSet, "signal_type"),
				resultSetInt(resultSet, "rank"),
				resultSetString(resultSet, "brief_text"),
				resultSetBoolean(resultSet, "fallback_used"),
				resultSetString(resultSet, "status")
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

	private record AlertAccumulator(
		UUID alertId, UUID studentId, String ruleId, String signalType, int rank,
		String brief, boolean briefFallback, String status, List<Evidence> evidence
	) {
		AlertAccumulator(
			UUID alertId, UUID studentId, String ruleId, String signalType, int rank,
			String brief, boolean briefFallback, String status
		) {
			this(alertId, studentId, ruleId, signalType, rank, brief, briefFallback,
				status, new ArrayList<>());
		}

		Alert toAlert() {
			return new Alert(alertId, studentId, ruleId, signalType, rank, brief,
				briefFallback, status, List.copyOf(evidence));
		}
	}
}
