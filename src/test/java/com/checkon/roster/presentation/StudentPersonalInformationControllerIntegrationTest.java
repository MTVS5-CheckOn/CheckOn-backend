package com.checkon.roster.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StudentPersonalInformationControllerIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f200-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198f200-0000-7000-8000-000000000002");
	private static final UUID STUDENT = UUID.fromString("0198f200-0000-7000-8000-000000000003");
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM student_personal_information");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		jdbc.update("INSERT INTO student_profiles(id,alias,created_at,updated_at) VALUES(?,?,?,?)",
			STUDENT, "표시 별칭", NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
		jdbc.update("INSERT INTO teacher_student_relationships(teacher_id,student_id,status,started_at,created_at) VALUES(?,?,'ACTIVE',?,?)",
			TEACHER, STUDENT, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
	}

	@Test
	void activeTeacherCanCreateAndUpdateARealName() throws Exception {
		mvc.perform(put("/api/v1/students/{studentId}/personal-information/name", STUDENT)
				.contentType("application/json")
				.content("{\"studentName\":\"김서연\"}")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.studentName").value("김서연"));

		mvc.perform(put("/api/v1/students/{studentId}/personal-information/name", STUDENT)
				.contentType("application/json")
				.content("{\"studentName\":\"김서윤\"}")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.studentName").value("김서윤"));

		assertThatSingleStoredName("김서윤");
	}

	@Test
	void invalidNamesAreRejectedWithoutExposingTheirValue() throws Exception {
		for (String body : List.of(
			"{\"studentName\":null}",
			"{\"studentName\":\"   \"}",
			"{\"studentName\":\" 김서연\"}"
		)) {
			mvc.perform(put("/api/v1/students/{studentId}/personal-information/name", STUDENT)
					.contentType("application/json").content(body)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	void anotherOrEndedTeacherReceivesTheSameNotFound() throws Exception {
		mvc.perform(put("/api/v1/students/{studentId}/personal-information/name", STUDENT)
				.contentType("application/json").content("{\"studentName\":\"김서연\"}")
				.with(teacherAuthentication(OTHER_TEACHER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STUDENT_NOT_FOUND"));

		jdbc.update("UPDATE teacher_student_relationships SET status='ENDED', ended_at=? WHERE student_id=?",
			NOW.plusSeconds(1).atOffset(ZoneOffset.UTC), STUDENT);
		mvc.perform(put("/api/v1/students/{studentId}/personal-information/name", STUDENT)
				.contentType("application/json").content("{\"studentName\":\"김서연\"}")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STUDENT_NOT_FOUND"));
	}

	private void assertThatSingleStoredName(String expected) {
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
			"SELECT real_name FROM student_personal_information", String.class
		)).isEqualTo(expected);
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM student_personal_information", Integer.class
		)).isOne();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(
		UUID teacherId
	) {
		UUID accountId = UUID.nameUUIDFromBytes(
			("account:" + teacherId).getBytes(StandardCharsets.UTF_8)
		);
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new AuthenticatedAccount(accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID()),
			null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
	}
}
