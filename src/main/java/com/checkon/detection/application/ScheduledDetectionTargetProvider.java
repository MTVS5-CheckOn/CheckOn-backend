package com.checkon.detection.application;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScheduledDetectionTargetProvider {

	private final JdbcTemplate jdbcTemplate;

	public ScheduledDetectionTargetProvider(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<UUID> findActiveTeacherProfileIds() {
		return jdbcTemplate.queryForList(
			"""
				SELECT DISTINCT teacher.id
				FROM teacher_profiles teacher
				JOIN accounts account ON account.id = teacher.account_id
				WHERE account.role = 'TEACHER'
				  AND account.status = 'ACTIVE'
				ORDER BY teacher.id
				""",
			UUID.class
		);
	}
}
