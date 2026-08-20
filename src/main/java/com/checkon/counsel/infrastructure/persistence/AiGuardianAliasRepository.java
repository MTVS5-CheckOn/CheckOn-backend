package com.checkon.counsel.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiGuardianAliasRepository {

	private final JdbcTemplate jdbcTemplate;

	public AiGuardianAliasRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<String> findAlias(UUID teacherId, UUID studentId) {
		return jdbcTemplate.query("""
			SELECT alias FROM ai_guardian_aliases
			WHERE teacher_id = ? AND student_id = ?
			""", (resultSet, rowNumber) -> resultSet.getString(1), teacherId, studentId)
			.stream().findFirst();
	}

	public void insertIfAbsent(UUID teacherId, UUID studentId, String alias, Instant createdAt) {
		jdbcTemplate.update("""
			INSERT INTO ai_guardian_aliases (teacher_id, student_id, alias, created_at)
			VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
			""", teacherId, studentId, alias, createdAt.atOffset(ZoneOffset.UTC));
	}
}
