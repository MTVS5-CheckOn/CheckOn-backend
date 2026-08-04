package com.checkon.learning.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.learning.application.LearningRecordSnapshotService;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LearningRecordControllerIntegrationTest {
	@Container @ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");
	private static final UUID TEACHER = UUID.fromString("0198c000-0000-7000-8000-000000000001");
	private static final UUID STUDENT = UUID.fromString("0198c000-0000-7000-8000-000000000002");
	private static final UUID CLASS_GROUP = UUID.fromString("0198c000-0000-7000-8000-000000000003");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198c000-0000-7000-8000-000000000011");
	private static final UUID OTHER_STUDENT = UUID.fromString("0198c000-0000-7000-8000-000000000012");
	private static final UUID OTHER_CLASS = UUID.fromString("0198c000-0000-7000-8000-000000000013");
	private static final UUID INACTIVE_STUDENT = UUID.fromString("0198c000-0000-7000-8000-000000000022");
	private static final Instant OCCURRED_AT = Instant.parse("2026-08-03T01:00:00Z");

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired LearningRecordSnapshotService snapshotService;

	@BeforeEach
	void fixtures() {
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		insertRoster(TEACHER, STUDENT, CLASS_GROUP, "ACTIVE");
		insertRoster(OTHER_TEACHER, OTHER_STUDENT, OTHER_CLASS, "ACTIVE");
		insertStudentRelationship(TEACHER, INACTIVE_STUDENT, "ENDED");
	}

	@Test
	void authenticatedTeacherRegistersManualRecordAndDetectionCanReadIt() throws Exception {
		String response = mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, CLASS_GROUP, null)))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
				"/api/v1/learning-records/[0-9a-f-]+")))
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(response.substring(response.indexOf(':') + 2, response.length() - 2));
		assertThat(jdbc.queryForObject(
			"SELECT teacher_id FROM learning_records WHERE id = ?", UUID.class, id
		)).isEqualTo(TEACHER);
		assertThat(jdbc.queryForObject(
			"SELECT source_type FROM learning_records WHERE id = ?", String.class, id
		)).isEqualTo("MANUAL");
		var snapshot = snapshotService.build(TEACHER, LocalDate.of(2026, 8, 3), "normal",
			OCCURRED_AT.minusSeconds(1), OCCURRED_AT.plusSeconds(1));
		assertThat(snapshot.learningEvents()).singleElement().satisfies(event ->
			assertThat(event.recordId()).contains(id.toString().replace("-", "")));
	}

	@Test
	void ignoresBodyTeacherIdAndAllowsNullAndDuplicateExternalReference() throws Exception {
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, null).replace("{", "{\"teacherId\":\"" + OTHER_TEACHER + "\",")))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, "same-ref")))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, "same-ref")))
			.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_records", Integer.class))
			.isEqualTo(3);
	}

	@Test
	void rejectsMalformedAndInvalidValues() throws Exception {
		for (String body : List.of(
			"{}",
			validRequest(STUDENT, null, null).replace("SOLVE", "UNKNOWN"),
			validRequest(STUDENT, null, null).replace("\"durationSec\": 120", "\"durationSec\": -1"),
			validRequest(STUDENT, null, null).replace("\"passageWordCount\": 800", "\"passageWordCount\": -1"),
			"{not-json"
		)) {
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	void concealsOtherTenantAndInactiveTargets() throws Exception {
		for (String body : List.of(
			validRequest(OTHER_STUDENT, null, null),
			validRequest(INACTIVE_STUDENT, null, null),
			validRequest(STUDENT, OTHER_CLASS, null)
		)) {
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEARNING_RECORD_TARGET_NOT_FOUND"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_records", Integer.class)).isZero();
	}

	@Test
	void requiresAuthenticationAndValidTeacherProfile() throws Exception {
		mockMvc.perform(post("/api/v1/learning-records")
				.contentType(MediaType.APPLICATION_JSON).content(validRequest(STUDENT, null, null)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(null)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, null)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TEACHER_PRINCIPAL"));
		mockMvc.perform(post("/api/v1/learning-records")
				.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
					new AuthenticatedAccount(UUID.randomUUID(), AccountRole.PARENT, null,
						UUID.randomUUID()),
					null,
					List.of(new SimpleGrantedAuthority("ROLE_PARENT"))
				)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, null)))
			.andExpect(status().isForbidden());
	}

	private String validRequest(UUID studentId, UUID classGroupId, String externalRef) {
		return """
			{
			  "studentId": "%s",
			  %s
			  "recordType": "SOLVE",
			  "occurredAt": "%s",
			  %s
			  "correct": true,
			  "durationSec": 120,
			  "passageWordCount": 800,
			  "areaTag": "reading"
			}
			""".formatted(studentId,
			classGroupId == null ? "" : "\"classGroupId\": \"" + classGroupId + "\",",
			OCCURRED_AT,
			externalRef == null ? "" : "\"externalRecordRef\": \"" + externalRef + "\",");
	}

	private void insertRoster(UUID teacherId, UUID studentId, UUID classId, String status) {
		RosterTestFixture.insertTeacher(jdbc, teacherId);
		insertStudentRelationship(teacherId, studentId, status);
		Instant now = Instant.parse("2026-08-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO class_groups (id, teacher_id, name, status, created_at, updated_at)
			VALUES (?, ?, '테스트 반', 'ACTIVE', ?, ?)
			""", classId, teacherId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
	}

	private void insertStudentRelationship(UUID teacherId, UUID studentId, String status) {
		Instant now = Instant.parse("2026-08-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, "학생-" + studentId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, ended_at, created_at)
			VALUES (uuidv7(), ?, ?, ?, ?, ?, ?)
			""", teacherId, studentId, status, now.atOffset(ZoneOffset.UTC),
			"ENDED".equals(status) ? now.plusSeconds(1).atOffset(ZoneOffset.UTC) : null,
			now.atOffset(ZoneOffset.UTC));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(
		UUID teacherId
	) {
		AuthenticatedAccount principal = new AuthenticatedAccount(
			UUID.randomUUID(), AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
	}
}
