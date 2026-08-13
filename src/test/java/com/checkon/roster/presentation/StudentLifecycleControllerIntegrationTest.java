package com.checkon.roster.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.support.RosterTestFixture;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class StudentLifecycleControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198f300-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER =
		UUID.fromString("0198f300-0000-7000-8000-000000000002");
	private static final UUID STUDENT =
		UUID.fromString("0198f300-0000-7000-8000-000000000003");
	private static final UUID CLASS =
		UUID.fromString("0198f300-0000-7000-8000-000000000004");
	private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM detection_student_status_history");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		jdbc.update("""
			INSERT INTO student_profiles(id, alias, created_at, updated_at)
			VALUES (?, '복귀 시연 학생', ?, ?)
			""", STUDENT, at(NOW), at(NOW));
		jdbc.update("""
			INSERT INTO class_groups(
			    id, teacher_id, name, subject, status, created_at, updated_at
			) VALUES (?, ?, '복귀 시연 반', '국어', 'ACTIVE', ?, ?)
			""", CLASS, TEACHER, at(NOW), at(NOW));
		jdbc.update("""
			INSERT INTO teacher_student_relationships(
			    teacher_id, student_id, status, started_at, created_at
			) VALUES (?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, at(NOW), at(NOW));
		jdbc.update("""
			INSERT INTO class_enrollments(
			    class_group_id, teacher_id, student_id, status, enrolled_at, created_at
			) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
			""", CLASS, TEACHER, STUDENT, at(NOW), at(NOW));
	}

	@Test
	@DisplayName("Given ACTIVE 학생, When 휴원 후 복귀하면, Then 관계와 반 소속 및 Detection 이력을 원자적으로 전환한다")
	void givenActiveStudent_whenPausedAndReturned_thenTransitionsRosterAndHistory() throws Exception {
		mvc.perform(post("/api/v1/students/{studentId}/pause", STUDENT)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.studentId").value(STUDENT.toString()))
			.andExpect(jsonPath("$.status").value("PAUSED"));

		assertThat(statusOf("teacher_student_relationships")).isEqualTo("PAUSED");
		assertThat(statusOf("class_enrollments")).isEqualTo("PAUSED");

		mvc.perform(post("/api/v1/students/{studentId}/return", STUDENT)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		assertThat(statusOf("teacher_student_relationships")).isEqualTo("ACTIVE");
		assertThat(statusOf("class_enrollments")).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForList("""
			SELECT from_status || '->' || to_status
			FROM detection_student_status_history
			ORDER BY occurred_at, id
			""", String.class)).containsExactly("enrolled->paused", "paused->returned");
	}

	@Test
	@DisplayName("Given ACTIVE 학생, When 복귀를 바로 요청하면, Then 409이고 이력을 만들지 않는다")
	void givenActiveStudent_whenReturnedWithoutPause_thenRejectsWithoutHistory() throws Exception {
		mvc.perform(post("/api/v1/students/{studentId}/return", STUDENT)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STUDENT_STATE"));

		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM detection_student_status_history", Integer.class
		)).isZero();
	}

	@Test
	@DisplayName("Given 다른 강사의 학생, When 휴원을 요청하면, Then 존재를 숨겨 404를 반환한다")
	void givenOtherTeachersStudent_whenPausing_thenConcealsExistence() throws Exception {
		mvc.perform(post("/api/v1/students/{studentId}/pause", STUDENT)
				.with(teacherAuthentication(OTHER_TEACHER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STUDENT_NOT_FOUND"));
	}

	private String statusOf(String table) {
		return jdbc.queryForObject(
			"SELECT status FROM " + table + " WHERE student_id = ?", String.class, STUDENT
		);
	}

	private java.time.OffsetDateTime at(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(
		UUID teacherId
	) {
		UUID accountId = UUID.nameUUIDFromBytes(
			("account:" + teacherId).getBytes(StandardCharsets.UTF_8)
		);
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new AuthenticatedAccount(accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID()),
			null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}
}
