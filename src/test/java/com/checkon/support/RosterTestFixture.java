package com.checkon.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Detection 통합 테스트가 실제 TeacherProfile FK를 만족하도록 최소 강사 행을 만든다.
 */
public final class RosterTestFixture {

	private RosterTestFixture() {
	}

	public static void insertTeacher(JdbcTemplate jdbcTemplate, UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(
			("account:" + teacherId).getBytes(StandardCharsets.UTF_8)
		);
		String email = teacherId + "@test.checkon.local";
		Instant now = Instant.parse("2026-07-31T00:00:00Z");
		jdbcTemplate.update(
			"""
				INSERT INTO accounts (id, email, role, status, created_at)
				VALUES (?, ?, 'TEACHER', 'ACTIVE', ?)
				ON CONFLICT (id) DO NOTHING
				""",
			accountId,
			email,
			now.atOffset(ZoneOffset.UTC)
		);
		jdbcTemplate.update(
			"""
				INSERT INTO teacher_profiles (
				    id, account_id, display_name, created_at, updated_at
				)
				VALUES (?, ?, '테스트 강사', ?, ?)
				ON CONFLICT (id) DO NOTHING
				""",
			teacherId,
			accountId,
			now.atOffset(ZoneOffset.UTC),
			now.atOffset(ZoneOffset.UTC)
		);
	}
}
