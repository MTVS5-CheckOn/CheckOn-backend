package com.checkon.counsel.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiParentLabelAliasRepository {

	private final JdbcTemplate jdbcTemplate;

	public AiParentLabelAliasRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<String> findAlias(UUID teacherId, UUID parentId) {
		return jdbcTemplate.query("""
			SELECT alias FROM ai_parent_label_aliases
			WHERE teacher_id = ? AND parent_id = ?
			""", (rs, row) -> rs.getString(1), teacherId, parentId).stream().findFirst();
	}

	public void insertIfAbsent(UUID teacherId, UUID parentId, String alias, Instant createdAt) {
		jdbcTemplate.update("""
			INSERT INTO ai_parent_label_aliases (teacher_id, parent_id, alias, created_at)
			VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
			""", teacherId, parentId, alias, createdAt.atOffset(ZoneOffset.UTC));
	}
}
