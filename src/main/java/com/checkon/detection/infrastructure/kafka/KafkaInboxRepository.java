package com.checkon.detection.infrastructure.kafka;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KafkaInboxRepository {

	private final JdbcTemplate jdbcTemplate;

	public KafkaInboxRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Returns false when the same Kafka event was already consumed successfully. */
	public boolean recordIfAbsent(UUID eventId, String topic, Instant receivedAt) {
		return jdbcTemplate.update("""
			INSERT INTO kafka_inbox_events (event_id, topic, received_at)
			VALUES (?, ?, ?)
			ON CONFLICT DO NOTHING
			""", eventId, topic, receivedAt.atOffset(ZoneOffset.UTC)) == 1;
	}
}
