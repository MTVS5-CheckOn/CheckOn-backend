package com.checkon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class CheckOnApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");
	private final JdbcTemplate jdbcTemplate;

	@Autowired
	CheckOnApplicationTests(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Test
	void contextLoads() {
	}

	@Test
	void postgresqlSupportsUuidV7() {
		Boolean isVersion7 = jdbcTemplate.queryForObject(
			"SELECT uuid_extract_version(uuidv7()) = 7",
			Boolean.class
		);

		assertTrue(Boolean.TRUE.equals(isVersion7));
	}

}
