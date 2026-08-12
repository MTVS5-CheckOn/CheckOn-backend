package com.checkon.problem.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProblemGenerationConsumedEventRepository {
	private final JdbcClient jdbcClient;
	public ProblemGenerationConsumedEventRepository(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

	public Optional<String> findPayloadHash(UUID eventId) {
		return jdbcClient.sql("SELECT payload_hash FROM problem_generation_consumed_events WHERE event_id = :eventId")
			.param("eventId", eventId).query(String.class).optional();
	}

	public boolean insert(UUID eventId, UUID teacherId, UUID requestId, String eventType,
		String payloadHash, Instant consumedAt) {
		return jdbcClient.sql("""
			INSERT INTO problem_generation_consumed_events (
			    event_id, teacher_id, problem_request_id, event_type, payload_hash, consumed_at
			) VALUES (:eventId, :teacherId, :requestId, :eventType, :payloadHash, :consumedAt)
			ON CONFLICT (event_id) DO NOTHING
			""").param("eventId", eventId).param("teacherId", teacherId).param("requestId", requestId)
			.param("eventType", eventType).param("payloadHash", payloadHash)
			.param("consumedAt", consumedAt.atOffset(ZoneOffset.UTC)).update() == 1;
	}
}
