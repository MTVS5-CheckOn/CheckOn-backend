package com.checkon.roster.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ClassManagementMigrationPreflightIntegrationTest {
	@Container
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Test
	void rejectsLegacyArchivedClassThatStillHasAnActiveEnrollment() throws Exception {
		flyway(MigrationVersion.fromVersion("12")).migrate();
		UUID accountId = UUID.randomUUID();
		UUID teacherId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		UUID classId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.of(
			2026, 8, 8, 0, 0, 0, 0, ZoneOffset.UTC
		);
		try (var connection = DriverManager.getConnection(
			POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()
		)) {
			try (var statement = connection.prepareStatement("""
				INSERT INTO accounts (id, email, role, status, created_at)
				VALUES (?, ?, 'TEACHER', 'ACTIVE', ?)
				""")) {
				statement.setObject(1, accountId);
				statement.setString(2, accountId + "@migration-preflight.test");
				statement.setObject(3, now);
				statement.executeUpdate();
			}
			try (var statement = connection.prepareStatement("""
				INSERT INTO teacher_profiles (
				    id, account_id, display_name, created_at, updated_at
				) VALUES (?, ?, '승격 사전검사 강사', ?, ?)
				""")) {
				statement.setObject(1, teacherId);
				statement.setObject(2, accountId);
				statement.setObject(3, now);
				statement.setObject(4, now);
				statement.executeUpdate();
			}
			try (var statement = connection.prepareStatement("""
				INSERT INTO student_profiles (
				    id, alias, grade, created_at, updated_at
				) VALUES (?, '승격-학생', 3, ?, ?)
				""")) {
				statement.setObject(1, studentId);
				statement.setObject(2, now);
				statement.setObject(3, now);
				statement.executeUpdate();
			}
			try (var statement = connection.prepareStatement("""
				INSERT INTO class_groups (
				    id, teacher_id, name, status, created_at, updated_at
				) VALUES (?, ?, '불일치 기존 반', 'ARCHIVED', ?, ?)
				""")) {
				statement.setObject(1, classId);
				statement.setObject(2, teacherId);
				statement.setObject(3, now);
				statement.setObject(4, now);
				statement.executeUpdate();
			}
			try (var statement = connection.prepareStatement("""
				INSERT INTO teacher_student_relationships (
				    teacher_id, student_id, status, started_at, created_at
				) VALUES (?, ?, 'ACTIVE', ?, ?)
				""")) {
				statement.setObject(1, teacherId);
				statement.setObject(2, studentId);
				statement.setObject(3, now);
				statement.setObject(4, now);
				statement.executeUpdate();
			}
			try (var statement = connection.prepareStatement("""
				INSERT INTO class_enrollments (
				    class_group_id, teacher_id, student_id, status,
				    enrolled_at, created_at
				) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
				""")) {
				statement.setObject(1, classId);
				statement.setObject(2, teacherId);
				statement.setObject(3, studentId);
				statement.setObject(4, now);
				statement.setObject(5, now);
				statement.executeUpdate();
			}
		}

		assertThatThrownBy(() -> flyway(null).migrate())
			.isInstanceOf(FlywayException.class)
			.hasStackTraceContaining("Cannot enforce class archive invariant");
	}

	private Flyway flyway(MigrationVersion target) {
		var configuration = Flyway.configure()
			.dataSource(
				POSTGRESQL.getJdbcUrl(),
				POSTGRESQL.getUsername(),
				POSTGRESQL.getPassword()
			);
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
	}
}
