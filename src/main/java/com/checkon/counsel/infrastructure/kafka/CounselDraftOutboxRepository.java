package com.checkon.counsel.infrastructure.kafka;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox for counsel draft requests — mirrors
 * {@code detection.infrastructure.kafka.KafkaOutboxRepository} but keyed to a
 * single {@code counsel_draft_jobs} row instead of a run/attempt pair, since
 * counsel has no multi-attempt run concept in v1 (one inquiry = one job).
 */
@Repository
public class CounselDraftOutboxRepository {

	private final JdbcTemplate jdbcTemplate;

	public CounselDraftOutboxRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(NewEvent event) {
		jdbcTemplate.update("""
			INSERT INTO counsel_draft_kafka_outbox_events (
			    id, teacher_id, counsel_draft_job_id,
			    topic, message_key, payload, status, publish_attempts,
			    next_attempt_at, created_at
			) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
			""",
			event.id(), event.teacherId(), event.jobId(), event.topic(),
			event.messageKey(), event.payload(), utc(event.createdAt()), utc(event.createdAt()));
	}

	@Transactional
	public List<ClaimedEvent> claimDue(Instant now, Instant staleBefore, int limit) {
		List<ClaimedEvent> events = jdbcTemplate.query("""
			SELECT id, teacher_id, counsel_draft_job_id,
			       topic, message_key, payload, publish_attempts
			FROM counsel_draft_kafka_outbox_events
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
			resultSet.getObject("counsel_draft_job_id", UUID.class),
			resultSet.getString("topic"),
			resultSet.getString("message_key"),
			resultSet.getString("payload"),
			resultSet.getInt("publish_attempts") + 1
		), utc(now), utc(staleBefore), limit);
		for (ClaimedEvent event : events) {
			jdbcTemplate.update("""
				UPDATE counsel_draft_kafka_outbox_events
				SET status = 'PROCESSING', publish_attempts = ?, locked_at = ?, last_error = NULL
				WHERE id = ?
				""", event.publishAttempts(), utc(now), event.id());
		}
		return events;
	}

	@Transactional
	public void markPublished(UUID id, Instant publishedAt) {
		jdbcTemplate.update("""
			UPDATE counsel_draft_kafka_outbox_events
			SET status = 'PUBLISHED', published_at = ?, locked_at = NULL, last_error = NULL
			WHERE id = ? AND status = 'PROCESSING'
			""", utc(publishedAt), id);
	}

	/** Used by the result consumer to verify a completion event's causation_id points at a real requested event for this job. */
	public boolean existsRequestedEvent(UUID eventId, UUID jobId) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM counsel_draft_kafka_outbox_events
			WHERE id = ? AND counsel_draft_job_id = ?
			""", Integer.class, eventId, jobId);
		return count != null && count == 1;
	}

	@Transactional
	public void reschedule(UUID id, int publishAttempts, int maxAttempts, Instant nextAttemptAt, String error) {
		boolean terminal = publishAttempts >= maxAttempts;
		jdbcTemplate.update("""
			UPDATE counsel_draft_kafka_outbox_events
			SET status = ?, next_attempt_at = ?, locked_at = NULL, last_error = ?
			WHERE id = ? AND status = 'PROCESSING'
			""", terminal ? "FAILED" : "PENDING", utc(nextAttemptAt), truncate(error), id);
	}

	private String truncate(String value) {
		if (value == null) return "Kafka publish failed";
		return value.length() <= 500 ? value : value.substring(0, 500);
	}

	private OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	public record NewEvent(
		UUID id,
		UUID teacherId,
		UUID jobId,
		String topic,
		String messageKey,
		String payload,
		Instant createdAt
	) {
	}

	public record ClaimedEvent(
		UUID id,
		UUID teacherId,
		UUID jobId,
		String topic,
		String messageKey,
		String payload,
		int publishAttempts
	) {
	}
}
