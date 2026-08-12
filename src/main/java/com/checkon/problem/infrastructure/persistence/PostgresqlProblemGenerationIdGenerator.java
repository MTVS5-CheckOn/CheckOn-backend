package com.checkon.problem.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.checkon.problem.application.ProblemGenerationIdGenerator;

@Component
public class PostgresqlProblemGenerationIdGenerator implements ProblemGenerationIdGenerator {
	private final JdbcClient jdbcClient;

	public PostgresqlProblemGenerationIdGenerator(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public List<UUID> nextIds(int count) {
		if (count < 1 || count > 100) {
			throw new IllegalArgumentException("count must be 1..100");
		}
		return jdbcClient.sql("SELECT uuidv7() FROM generate_series(1, :count)")
			.param("count", count).query(UUID.class).list();
	}
}
