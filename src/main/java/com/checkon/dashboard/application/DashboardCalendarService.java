package com.checkon.dashboard.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.dashboard.application.DashboardCalendar.Item;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

@Service
public class DashboardCalendarService {
	private final JdbcTemplate jdbcTemplate;
	private final TeacherTenantDatabaseContext tenantContext;

	public DashboardCalendarService(
		JdbcTemplate jdbcTemplate,
		TeacherTenantDatabaseContext tenantContext
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public DashboardCalendar getCalendar(
		UUID teacherProfileId,
		LocalDate startedAt,
		LocalDate endedAt
	) {
		Objects.requireNonNull(teacherProfileId, "teacherProfileId must not be null");
		validateRange(startedAt, endedAt);
		tenantContext.setCurrentTeacher(teacherProfileId);

		Map<LocalDate, Long> counts = new HashMap<>();
		jdbcTemplate.query(
			"""
				SELECT run.analysis_date AS event_date, COUNT(alert.id) AS event_count
				FROM engagement_alerts alert
				JOIN detection_signal_results signal
				  ON signal.id = alert.detection_signal_result_id
				JOIN detection_runs run
				  ON run.id = signal.detection_run_id
				WHERE alert.teacher_id = ?
				  AND run.teacher_id = ?
				  AND run.analysis_date BETWEEN ? AND ?
				GROUP BY run.analysis_date
				ORDER BY run.analysis_date
				""",
			ps -> {
				ps.setObject(1, teacherProfileId);
				ps.setObject(2, teacherProfileId);
				ps.setObject(3, startedAt);
				ps.setObject(4, endedAt);
			},
			rs -> {
				counts.put(
					rs.getObject("event_date", LocalDate.class),
					rs.getLong("event_count")
				);
			}
		);

		List<Item> items = startedAt.datesUntil(endedAt.plusDays(1))
			.map(date -> new Item(date, counts.getOrDefault(date, 0L)))
			.toList();
		return new DashboardCalendar(startedAt, endedAt, items);
	}

	static void validateRange(LocalDate startedAt, LocalDate endedAt) {
		if (startedAt == null || endedAt == null) {
			throw new InvalidDashboardCalendarRangeException(
				"Both startedAt and endedAt are required."
			);
		}
		if (startedAt.isAfter(endedAt)) {
			throw new InvalidDashboardCalendarRangeException(
				"startedAt must not be after endedAt."
			);
		}
	}
}
