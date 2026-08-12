package com.checkon.problem.infrastructure.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProblemGenerationOutboxRepository {
	private final JdbcClient jdbcClient;
	public ProblemGenerationOutboxRepository(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

	public void insert(NewOutboxEvent event) {
		jdbcClient.sql("""
			INSERT INTO problem_generation_outbox (
			    id, teacher_id, problem_request_id, event_type, schema_version,
			    event_key, payload, status, next_attempt_at, created_at
			) VALUES (:id, :teacherId, :requestId, :eventType, :schemaVersion,
			    :eventKey, CAST(:payload AS jsonb), 'PENDING', :createdAt, :createdAt)
			""").param("id", event.id()).param("teacherId", event.teacherId())
			.param("requestId", event.requestId()).param("eventType", event.eventType())
			.param("schemaVersion", event.schemaVersion()).param("eventKey", event.eventKey())
			.param("payload", event.payload()).param("createdAt", databaseTime(event.createdAt())).update();
	}

	public List<UUID> findAllTeacherIds() {
		return jdbcClient.sql("SELECT id FROM teacher_profiles ORDER BY id").query(UUID.class).list();
	}

	public List<OutboxMessage> claimBatch(UUID teacherId, Instant now, Instant staleBefore, int limit) {
		return jdbcClient.sql("""
			WITH candidates AS (
			    SELECT id FROM problem_generation_outbox
			    WHERE teacher_id = :teacherId AND (
			      (status = 'PENDING' AND next_attempt_at <= :now)
			      OR (status = 'PUBLISHING' AND claimed_at <= :staleBefore)
			    ) ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT :limit
			)
			UPDATE problem_generation_outbox outbox
			SET status = 'PUBLISHING', claimed_at = :now,
			    attempt_count = outbox.attempt_count + 1
			FROM candidates WHERE outbox.id = candidates.id
			RETURNING outbox.id, outbox.teacher_id, outbox.problem_request_id,
			          outbox.event_type, outbox.schema_version, outbox.event_key,
			          outbox.payload::text AS payload, outbox.attempt_count, outbox.created_at
			""").param("teacherId", teacherId).param("now", databaseTime(now))
			.param("staleBefore", databaseTime(staleBefore)).param("limit", limit)
			.query(ProblemGenerationOutboxRepository::mapMessage).list();
	}

	public void markPublished(UUID eventId, UUID teacherId, Instant publishedAt) {
		jdbcClient.sql("""
			UPDATE problem_generation_outbox
			SET status = 'PUBLISHED', published_at = :now, last_error = NULL
			WHERE id = :eventId AND teacher_id = :teacherId AND status = 'PUBLISHING'
			""").param("eventId", eventId).param("teacherId", teacherId)
			.param("now", databaseTime(publishedAt)).update();
	}

	public void markPending(UUID eventId, UUID teacherId, Instant nextAttemptAt, String error) {
		jdbcClient.sql("""
			UPDATE problem_generation_outbox
			SET status = 'PENDING', next_attempt_at = :nextAttemptAt,
			    claimed_at = NULL, last_error = :error
			WHERE id = :eventId AND teacher_id = :teacherId AND status = 'PUBLISHING'
			""").param("eventId", eventId).param("teacherId", teacherId)
			.param("nextAttemptAt", databaseTime(nextAttemptAt)).param("error", truncate(error)).update();
	}

	public void markDead(UUID eventId, UUID teacherId, String error) {
		jdbcClient.sql("""
			UPDATE problem_generation_outbox
			SET status = 'DEAD', claimed_at = NULL, last_error = :error
			WHERE id = :eventId AND teacher_id = :teacherId AND status = 'PUBLISHING'
			""").param("eventId", eventId).param("teacherId", teacherId)
			.param("error", truncate(error)).update();
	}

	private static OutboxMessage mapMessage(ResultSet rs, int row) throws SQLException {
		return new OutboxMessage(rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
			rs.getObject("problem_request_id", UUID.class), rs.getString("event_type"), rs.getString("schema_version"),
			rs.getString("event_key"), rs.getString("payload"), rs.getInt("attempt_count"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}
	private static String truncate(String error) {
		String safe = error == null || error.isBlank() ? "Kafka publish failed" : error;
		return safe.substring(0, Math.min(safe.length(), 500));
	}
	private static OffsetDateTime databaseTime(Instant value) { return value.atOffset(ZoneOffset.UTC); }

	public record NewOutboxEvent(UUID id, UUID teacherId, UUID requestId, String eventType,
		String schemaVersion, String eventKey, String payload, Instant createdAt) { }
	public record OutboxMessage(UUID id, UUID teacherId, UUID requestId, String eventType,
		String schemaVersion, String eventKey, String payload, int attemptCount, Instant createdAt) { }
}
