package com.checkon.detection.infrastructure.kafka;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stable, opaque routing alias used in every AI-facing Kafka event. */
@Service
public class AiTenantAliasService {

	private static final int MAX_ATTEMPTS = 5;
	private final SecureRandom secureRandom = new SecureRandom();
	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public AiTenantAliasService(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	public String getOrCreate(UUID teacherId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		String existing = findAlias(teacherId);
		if (existing != null) return existing;

		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			jdbcTemplate.update("""
				INSERT INTO ai_tenant_aliases (teacher_id, alias, created_at)
				VALUES (?, ?, ?)
				ON CONFLICT DO NOTHING
				""", teacherId, candidate(), Instant.now(clock).atOffset(ZoneOffset.UTC));
			existing = findAlias(teacherId);
			if (existing != null) return existing;
		}
		throw new IllegalStateException("AI tenant alias could not be allocated");
	}

	public UUID requireTeacherId(String tenantAlias) {
		if (tenantAlias == null || tenantAlias.isBlank()) {
			throw new IllegalArgumentException("tenantAlias must not be blank");
		}
		UUID teacherId = jdbcTemplate.query("""
			SELECT teacher_id FROM ai_tenant_aliases WHERE alias = ?
			""", resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
			tenantAlias);
		if (teacherId == null) {
			throw new IllegalArgumentException("Unknown AI tenant alias");
		}
		return teacherId;
	}

	private String findAlias(UUID teacherId) {
		return jdbcTemplate.query("""
			SELECT alias FROM ai_tenant_aliases WHERE teacher_id = ?
			""", resultSet -> resultSet.next() ? resultSet.getString(1) : null, teacherId);
	}

	private String candidate() {
		byte[] bytes = new byte[16];
		secureRandom.nextBytes(bytes);
		return "tn_" + HexFormat.of().formatHex(bytes);
	}
}
