package com.checkon.problem.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("문제 출제 요청 API")
class ProblemGenerationRequestControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198f900-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER =
		UUID.fromString("0198f900-0000-7000-8000-000000000002");
	private static final UUID STUDENT =
		UUID.fromString("0198f900-0000-7000-8000-000000000011");
	private static final UUID OTHER_STUDENT =
		UUID.fromString("0198f900-0000-7000-8000-000000000012");
	private static final UUID CLASS =
		UUID.fromString("0198f900-0000-7000-8000-000000000021");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-11T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM problem_assignments");
		jdbc.update("DELETE FROM saved_problem_set_items");
		jdbc.update("DELETE FROM saved_problem_sets");
		jdbc.update("DELETE FROM problem_generation_item_options");
		jdbc.update("DELETE FROM problem_generation_items");
		jdbc.update("DELETE FROM problem_generation_consumed_events");
		jdbc.update("DELETE FROM problem_generation_outbox");
		jdbc.update("DELETE FROM problem_generation_executions");
		jdbc.update("DELETE FROM problem_generation_request_targets");
		jdbc.update("DELETE FROM problem_generation_requests");
		jdbc.update("DELETE FROM ai_class_aliases");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		insertStudentAndRelationship(STUDENT, TEACHER);
		insertStudentAndRelationship(OTHER_STUDENT, OTHER_TEACHER);
	}

	@Nested
	@DisplayName("Given 활성 학생 대상이 있을 때")
	class GivenActiveStudentTarget {

		@Test
		@DisplayName("When 출제를 요청하면 Then 요청과 Outbox를 원자적으로 저장하고 내부 식별자를 노출하지 않는다")
		void createsRequestAndOutboxWithoutInternalIdentifiers() throws Exception {
			MvcResult result = create(TEACHER, STUDENT, "student-request-key-0001", 3)
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/problem-requests/")))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.targetKind").value("STUDENT"))
				.andExpect(jsonPath("$.replayed").value(false))
				.andReturn();

			UUID requestId = requestId(result);
			String requestPayload = jdbc.queryForObject(
				"SELECT request_payload::text FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			);
			String outboxPayload = jdbc.queryForObject(
				"SELECT payload::text FROM problem_generation_outbox WHERE problem_request_id = ?",
				String.class,
				requestId
			);
			String targetRef = jdbc.queryForObject(
				"SELECT target_ref FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			);
			String tenantAlias = jdbc.queryForObject(
				"SELECT tenant_alias FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			);

			assertThat(targetRef).matches("st_[0-9a-f]{32}");
			assertThat(tenantAlias).matches("tn_[0-9a-f]{32}");
			assertThat(requestPayload)
				.contains("teacher_manual", "language", "mcq", "skill.grammar.001")
				.doesNotContain(TEACHER.toString(), STUDENT.toString());
			assertThat(outboxPayload)
				.contains(requestId.toString(), tenantAlias, targetRef)
				.doesNotContain(TEACHER.toString(), STUDENT.toString());
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_outbox WHERE problem_request_id = ?",
				Integer.class,
				requestId
			)).isEqualTo(1);
		}

		@Test
		@DisplayName("When 같은 멱등 키와 같은 요청을 반복하면 Then 같은 요청을 재사용한다")
		void replaysTheSameRequest() throws Exception {
			UUID first = requestId(create(TEACHER, STUDENT, "student-request-key-0002", 2)
				.andExpect(status().isAccepted()).andReturn());

			create(TEACHER, STUDENT, "student-request-key-0002", 2)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.requestId").value(first.toString()))
				.andExpect(jsonPath("$.replayed").value(true));

			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_requests WHERE teacher_id = ?",
				Integer.class,
				TEACHER
			)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_outbox", Integer.class))
				.isEqualTo(1);
		}

		@Test
		@DisplayName("When 같은 멱등 키에 다른 요청을 보내면 Then 충돌로 거절한다")
		void rejectsAnIdempotencyKeyReusedWithAnotherPayload() throws Exception {
			create(TEACHER, STUDENT, "student-request-key-0003", 2)
				.andExpect(status().isAccepted());

			create(TEACHER, STUDENT, "student-request-key-0003", 3)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		}

		@Test
		@DisplayName("When 멱등 키가 없으면 Then 저장 없이 요청을 거절한다")
		void rejectsARequestWithoutIdempotencyKey() throws Exception {
			mvc.perform(post("/api/v1/problem-requests")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(STUDENT, 2)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_requests", Integer.class))
				.isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_outbox", Integer.class))
				.isZero();
		}
	}

	@Nested
	@DisplayName("Given 활성 클래스 대상이 있을 때")
	class GivenActiveClassTarget {

		@Test
		@DisplayName("When 클래스 출제를 요청하면 Then cl_ 가명만 AI 요청에 저장한다")
		void createsAnOpaqueClassAlias() throws Exception {
			jdbc.update("""
				INSERT INTO class_groups
				    (id, teacher_id, name, subject, status, created_at, updated_at)
				VALUES (?, ?, '문법반', '국어', 'ACTIVE', ?, ?)
				""", CLASS, TEACHER, time(), time());

			MvcResult result = mvc.perform(post("/api/v1/problem-requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "class-request-key-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody("CLASS", CLASS, 2)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.targetKind").value("CLASS"))
				.andReturn();

			String payload = jdbc.queryForObject(
				"SELECT request_payload::text FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId(result)
			);
			assertThat(payload).containsPattern("cl_[0-9a-f]{32}").doesNotContain(CLASS.toString());
		}
	}

	@Nested
	@DisplayName("Given 다른 강사의 대상과 요청이 있을 때")
	class GivenAnotherTeachersResources {

		@Test
		@DisplayName("When 다른 강사의 학생으로 출제를 요청하면 Then 존재를 숨겨 404를 반환한다")
		void hidesAnotherTeachersStudent() throws Exception {
			create(TEACHER, OTHER_STUDENT, "cross-tenant-key-0001", 2)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PROBLEM_TARGET_NOT_FOUND"));
		}

		@Test
		@DisplayName("When 다른 강사가 요청을 조회하면 Then 존재를 숨겨 404를 반환한다")
		void hidesAnotherTeachersRequest() throws Exception {
			UUID requestId = requestId(create(TEACHER, STUDENT, "cross-tenant-key-0002", 2)
				.andExpect(status().isAccepted()).andReturn());

			mvc.perform(get("/api/v1/problem-requests/{requestId}", requestId)
					.with(teacherAuthentication(OTHER_TEACHER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PROBLEM_REQUEST_NOT_FOUND"));
		}
	}

	private org.springframework.test.web.servlet.ResultActions create(
		UUID teacherId,
		UUID studentId,
		String idempotencyKey,
		int count
	) throws Exception {
		return mvc.perform(post("/api/v1/problem-requests")
			.with(teacherAuthentication(teacherId))
			.header("Idempotency-Key", idempotencyKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody(studentId, count)));
	}

	private String requestBody(UUID studentId, int count) {
		return requestBody("STUDENT", studentId, count);
	}

	private String requestBody(String targetKind, UUID targetId, int count) {
		return """
			{
			  "targetKind": "%s",
			  "targetId": "%s",
			  "manualTargets": ["skill.grammar.001"],
			  "taxonomyVersion": "2026.08",
			  "typeTags": ["CONCEPT"],
			  "count": %d,
			  "requestedDifficulty": "MEDIUM"
			}
			""".formatted(targetKind, targetId, count);
	}

	private UUID requestId(MvcResult result) throws Exception {
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
	}

	private void insertStudentAndRelationship(UUID studentId, UUID teacherId) {
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, "student-" + studentId, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", teacherId, studentId, time(), time());
	}

	private static java.time.OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(
			("account:" + teacherId).getBytes(StandardCharsets.UTF_8)
		);
		var principal = new AuthenticatedAccount(
			accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID()
		);
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}
}
