package com.checkon.detection.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.checkon.detection.application.DetectionIdGenerator;

@Component
public class PostgresqlDetectionIdGenerator implements DetectionIdGenerator {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlDetectionIdGenerator(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<UUID> nextIds(int count) {
		if (count < 0) {
			throw new IllegalArgumentException("count must not be negative");
		}
		if (count == 0) {
			return List.of();
		}

		return jdbcTemplate.query(
			"SELECT uuidv7() FROM generate_series(1, ?)",
			(resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
			count
		);
	}
}
