package com.checkon.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class TeacherTenantDatabaseContextIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired
	TeacherTenantDatabaseContext tenantDatabaseContext;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void setsBoundTeacherIdOnlyInsideTheActiveTransaction() {
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> tenantDatabaseContext.setCurrentTeacher(teacherId))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("active database transaction");

		transactionTemplate.executeWithoutResult(status -> {
			tenantDatabaseContext.setCurrentTeacher(teacherId);
			assertThat(jdbcTemplate.queryForObject(
				"SELECT current_checkon_teacher_id()",
				UUID.class
			)).isEqualTo(teacherId);
		});

		String setting = jdbcTemplate.queryForObject(
			"SELECT current_setting('checkon.current_teacher_id', true)",
			String.class
		);
		assertThat(setting).isBlank();
	}
}
