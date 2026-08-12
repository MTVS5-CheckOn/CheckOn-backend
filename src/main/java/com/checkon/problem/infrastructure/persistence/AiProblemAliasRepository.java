package com.checkon.problem.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiProblemAliasRepository {
	private final JdbcTemplate jdbcTemplate;

	public AiProblemAliasRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

	public Optional<String> findTenantAlias(UUID teacherId) {
		return jdbcTemplate.query("SELECT alias FROM ai_tenant_aliases WHERE teacher_id = ?",
			(resultSet, rowNumber) -> resultSet.getString(1), teacherId).stream().findFirst();
	}

	public void insertTenantAliasIfAbsent(UUID teacherId, String alias, Instant createdAt) {
		jdbcTemplate.update("""
			INSERT INTO ai_tenant_aliases (teacher_id, alias, created_at)
			VALUES (?, ?, ?) ON CONFLICT DO NOTHING
			""", teacherId, alias, createdAt.atOffset(ZoneOffset.UTC));
	}

	public Optional<String> findClassAlias(UUID teacherId, UUID classGroupId) {
		return jdbcTemplate.query("""
			SELECT alias FROM ai_class_aliases
			WHERE teacher_id = ? AND class_group_id = ?
			""", (resultSet, rowNumber) -> resultSet.getString(1), teacherId, classGroupId)
			.stream().findFirst();
	}

	public void insertClassAliasIfAbsent(UUID teacherId, UUID classGroupId, String alias, Instant createdAt) {
		jdbcTemplate.update("""
			INSERT INTO ai_class_aliases (teacher_id, class_group_id, alias, created_at)
			VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
			""", teacherId, classGroupId, alias, createdAt.atOffset(ZoneOffset.UTC));
	}
}
