package com.checkon.detection.infrastructure.kafka;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KafkaOutboxRepository {

	private final JdbcTemplate jdbcTemplate;

	public KafkaOutboxRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(NewEvent event) {
		jdbcTemplate.update("""
			INSERT INTO kafka_outbox_events (
			    id, teacher_id, detection_run_id, detection_attempt_id,
			    topic, message_key, payload, status, publish_attempts,
			    next_attempt_at, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
			""",
			event.id(), event.teacherId(), event.runId(), event.attemptId(), event.topic(),
			event.messageKey(), event.payload(), utc(event.createdAt()), utc(event.createdAt()));
	}

	@Transactional
	public List<ClaimedEvent> claimDue(
		Instant now,
		Instant staleBefore,
		int limit
	) {
		List<ClaimedEvent> events = jdbcTemplate.query("""
			SELECT id, teacher_id, detection_run_id, detection_attempt_id,
			       topic, message_key, payload, publish_attempts
			FROM kafka_outbox_events
			WHERE (
			    status = 'PENDING' AND next_attempt_at <= ?
			) OR (
			    status = 'PROCESSING' AND locked_at <= ?
			)
			ORDER BY created_at, id
			FOR UPDATE SKIP LOCKED
			LIMIT ?
			""", (resultSet, rowNumber) -> new ClaimedEvent(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("teacher_id", UUID.class),
			resultSet.getObject("detection_run_id", UUID.class),
			resultSet.getObject("detection_attempt_id", UUID.class),
			resultSet.getString("topic"),
			resultSet.getString("message_key"),
			resultSet.getString("payload"),
			resultSet.getInt("publish_attempts") + 1
		), utc(now), utc(staleBefore), limit);
		for (ClaimedEvent event : events) {
			jdbcTemplate.update("""
				UPDATE kafka_outbox_events
				SET status = 'PROCESSING', publish_attempts = ?, locked_at = ?, last_error = NULL
				WHERE id = ?
				""", event.publishAttempts(), utc(now), event.id());
		}
		return events;
	}

	@Transactional
	public void markPublished(UUID id, Instant publishedAt) {
		jdbcTemplate.update("""
			UPDATE kafka_outbox_events
			SET status = 'PUBLISHED', published_at = ?, locked_at = NULL, last_error = NULL
			WHERE id = ? AND status = 'PROCESSING'
			""", utc(publishedAt), id);
	}

	public boolean existsRequestedEvent(UUID eventId, UUID runId, UUID attemptId) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM kafka_outbox_events
			WHERE id = ?
			  AND detection_run_id = ?
			  AND detection_attempt_id = ?
			""", Integer.class, eventId, runId, attemptId);
		return count != null && count == 1;
	}

	@Transactional
	public void reschedule(
		UUID id,
		int publishAttempts,
		int maxAttempts,
		Instant nextAttemptAt,
		String error
	) {
		boolean terminal = publishAttempts >= maxAttempts;
		jdbcTemplate.update("""
			UPDATE kafka_outbox_events
			SET status = ?, next_attempt_at = ?, locked_at = NULL, last_error = ?
			WHERE id = ? AND status = 'PROCESSING'
			""", terminal ? "FAILED" : "PENDING", utc(nextAttemptAt), truncate(error), id);
	}

	private String truncate(String value) {
		if (value == null) return "Kafka publish failed";
		return value.length() <= 500 ? value : value.substring(0, 500);
	}

	private java.time.OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	public record NewEvent(
		UUID id,
		UUID teacherId,
		UUID runId,
		UUID attemptId,
		String topic,
		String messageKey,
		String payload,
		Instant createdAt
	) {
	}

	public record ClaimedEvent(
		UUID id,
		UUID teacherId,
		UUID runId,
		UUID attemptId,
		String topic,
		String messageKey,
		String payload,
		int publishAttempts
	) {
	}
}
