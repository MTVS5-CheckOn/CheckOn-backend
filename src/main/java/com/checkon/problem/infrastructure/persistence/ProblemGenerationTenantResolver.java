package com.checkon.problem.infrastructure.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProblemGenerationTenantResolver {
	private final JdbcClient jdbcClient;
	public ProblemGenerationTenantResolver(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

	public Optional<UUID> resolve(UUID requestId, String tenantAlias) {
		return jdbcClient.sql("SELECT resolve_problem_generation_teacher(:id, :alias)")
			.param("id", requestId).param("alias", tenantAlias).query(UUID.class)
			.optional().filter(Objects::nonNull);
	}
}
