package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class ScheduledDetectionTargetProviderIntegrationTest {

	private static final UUID ACTIVE_ACCOUNT =
		UUID.fromString("0198d000-0000-7000-8000-000000000001");
	private static final UUID ACTIVE_TEACHER =
		UUID.fromString("0198d000-0000-7000-8000-000000000002");
	private static final UUID SUSPENDED_ACCOUNT =
		UUID.fromString("0198d000-0000-7000-8000-000000000003");
	private static final UUID SUSPENDED_TEACHER =
		UUID.fromString("0198d000-0000-7000-8000-000000000004");
	private static final UUID PARENT_ACCOUNT =
		UUID.fromString("0198d000-0000-7000-8000-000000000005");
	private static final UUID PARENT_PROFILE =
		UUID.fromString("0198d000-0000-7000-8000-000000000006");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired JdbcTemplate jdbc;
	@Autowired ScheduledDetectionTargetProvider provider;

	@BeforeEach
	void fixtures() {
		jdbc.update("DELETE FROM teacher_profiles WHERE id IN (?, ?, ?)",
			ACTIVE_TEACHER, SUSPENDED_TEACHER, PARENT_PROFILE);
		jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)",
			ACTIVE_ACCOUNT, SUSPENDED_ACCOUNT, PARENT_ACCOUNT);
		insertAccount(ACTIVE_ACCOUNT, "active-scheduler@checkon.test", "TEACHER", "ACTIVE");
		insertAccount(SUSPENDED_ACCOUNT, "suspended-scheduler@checkon.test", "TEACHER", "SUSPENDED");
		insertAccount(PARENT_ACCOUNT, "parent-scheduler@checkon.test", "PARENT", "ACTIVE");
		insertProfile(ACTIVE_TEACHER, ACTIVE_ACCOUNT, "활성 강사");
		insertProfile(SUSPENDED_TEACHER, SUSPENDED_ACCOUNT, "정지 강사");
		insertProfile(PARENT_PROFILE, PARENT_ACCOUNT, "부모 프로필");
	}

	@Test
	void returnsOnlyProfilesBackedByActiveTeacherAccounts() {
		assertThat(provider.findActiveTeacherProfileIds())
			.contains(ACTIVE_TEACHER)
			.doesNotContain(SUSPENDED_TEACHER, PARENT_PROFILE);
	}

	private void insertAccount(UUID id, String email, String role, String status) {
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		jdbc.update("""
			INSERT INTO accounts (id, email, role, status, created_at)
			VALUES (?, ?, ?, ?, ?)
			""", id, email, role, status, now.atOffset(ZoneOffset.UTC));
	}

	private void insertProfile(UUID id, UUID accountId, String displayName) {
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		jdbc.update("""
			INSERT INTO teacher_profiles
			(id, account_id, display_name, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?)
			""", id, accountId, displayName, now.atOffset(ZoneOffset.UTC),
			now.atOffset(ZoneOffset.UTC));
	}
}
