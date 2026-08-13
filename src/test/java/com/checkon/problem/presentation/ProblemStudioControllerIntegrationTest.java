package com.checkon.problem.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.checkon.problem.integration.kafka.ProblemGenerationResultEventHandler;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("프론트 문제 출제 스튜디오 4단계")
class ProblemStudioControllerIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198fb00-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198fb00-0000-7000-8000-000000000002");
	private static final UUID STUDENT = UUID.fromString("0198fb00-0000-7000-8000-000000000011");
	private static final UUID CLASS = UUID.fromString("0198fb00-0000-7000-8000-000000000021");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-01T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper objectMapper;
	@Autowired ProblemGenerationResultEventHandler resultHandler;

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
		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM student_personal_information");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		insertRoster();
	}

	@Nested
	@DisplayName("Given 최근 8주 학습 기록이 있는 활성 학생이 있을 때")
	class GivenAnActiveStudentWithLearningRecords {
		@Test
		@DisplayName("When Step 1을 조회하면 Then 학생 화면 정보와 표본 기반 약점 셀을 반환한다")
		void returnsStudentAndWeaknessData() throws Exception {
			insertLearning("독서", "FACT", 10, 8);
			insertLearning("독서", "INFER", 5, 2);
			insertLearning("문학", "CRITIC", 10, 2);

			mvc.perform(get("/api/v1/problem-studio/students")
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].studentId").value(STUDENT.toString()))
				.andExpect(jsonPath("$.content[0].studentName").value("홍길동"))
				.andExpect(jsonPath("$.content[0].className").value("수능 국어 대비 반"))
				.andExpect(jsonPath("$.content[0].subject").value("국어"))
				.andExpect(jsonPath("$.content[0].recentSignalCount").value(0));

			mvc.perform(get("/api/v1/problem-studio/students/{studentId}/weakness-analysis", STUDENT)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.minimumSampleSize").value(10))
				.andExpect(jsonPath("$.generationCapabilities.length()").value(2))
				.andExpect(jsonPath("$.generationCapabilities[?(@.areaTag == 'language' && @.typeTag == 'CONCEPT')].recommendedMaximumCount")
					.value(3))
				.andExpect(jsonPath("$.generationCapabilities[?(@.areaTag == 'language' && @.typeTag == 'INFER')].maximumCount")
					.value(20))
				.andExpect(jsonPath("$.cells[?(@.areaTag == '독서' && @.typeTag == 'FACT')].evaluation")
					.value("GOOD"))
				.andExpect(jsonPath("$.cells[?(@.areaTag == '독서' && @.typeTag == 'INFER')].evaluation")
					.value("ON_HOLD"))
				.andExpect(jsonPath("$.cells[?(@.areaTag == '문학' && @.typeTag == 'CRITIC')].evaluation")
					.value("WEAK_SIGNAL"));
		}
	}

	@Nested
	@DisplayName("Given 프론트 영역별 출제 조건이 있을 때")
	class GivenFrontendGenerationTargets {
		@Test
		@DisplayName("When Step 2부터 Step 4까지 수행하면 Then 투영·선택·저장·발행이 멱등하게 완성된다")
		void completesReviewSaveAndPublishIdempotently() throws Exception {
			MvcResult created = mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-request-key-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "studentId":"%s",
						  "targets":[
						    {"areaTag":"LANGUAGE","typeTag":"CONCEPT","count":3},
						    {"areaTag":"language","typeTag":"INFER","count":2}
						  ],
						  "difficulty":"LOW"
						}
						""".formatted(STUDENT)))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/problem-requests/")))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn();
			UUID requestId = UUID.fromString(objectMapper.readTree(
				created.getResponse().getContentAsString()).get("requestId").asText());

			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_request_targets WHERE problem_request_id = ?",
				Integer.class, requestId)).isEqualTo(2);
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_executions WHERE problem_request_id = ?",
				Integer.class, requestId)).isEqualTo(2);
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_outbox WHERE problem_request_id = ? AND problem_execution_id IS NOT NULL",
				Integer.class, requestId)).isEqualTo(2);
			List<String> childPayloads = jdbc.queryForList("""
				SELECT payload::text FROM problem_generation_outbox
				WHERE problem_request_id = ? ORDER BY created_at, id
				""", String.class, requestId);
			assertThat(childPayloads).allSatisfy(payload -> assertThat(payload)
				.contains("problem_execution_id", "target_index", "teacher_manual")
				.doesNotContain("teacher_weakness_selection", "\"area_tag\": \"mixed\""));
			String requestPayload = jdbc.queryForObject(
				"SELECT request_payload::text FROM problem_generation_requests WHERE id = ?",
				String.class, requestId);
			assertThat(requestPayload).contains("generation_targets", "teacher_weakness_selection", "language", "\"count\": 5")
				.doesNotContain(STUDENT.toString());

			String tenantAlias = jdbc.queryForObject(
				"SELECT tenant_alias FROM problem_generation_requests WHERE id = ?", String.class, requestId);
			List<UUID> executionIds = jdbc.queryForList("""
				SELECT id FROM problem_generation_executions WHERE problem_request_id=? ORDER BY target_index
				""", UUID.class, requestId);
			resultHandler.handle(childSuccessEvent(requestId, executionIds.get(0), 0, tenantAlias));
			resultHandler.handle(childFailedEvent(requestId, executionIds.get(1), 1, tenantAlias));

			mvc.perform(get("/api/v1/problem-studio/requests/{requestId}/review", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestStatus").value("PARTIAL_SUCCESS"))
				.andExpect(jsonPath("$.projectionStatus").value("PROJECTED"))
				.andExpect(jsonPath("$.counts.passed").value(1))
				.andExpect(jsonPath("$.counts.reviewRequired").value(1))
				.andExpect(jsonPath("$.counts.unverifiable").value(1))
				.andExpect(jsonPath("$.counts.excluded").value(1))
				.andExpect(jsonPath("$.items[0].stem").value("음운 변동 유형을 고르세요."))
				.andExpect(jsonPath("$.items[0].correctAnswerText").value("정답"))
				.andExpect(jsonPath("$.items[0].options[2].correct").value(true));

			List<UUID> selected = jdbc.queryForList("""
				SELECT id FROM problem_generation_items
				WHERE problem_request_id = ? AND validation_status IN ('PASSED','REVIEW_REQUIRED')
				ORDER BY ordinal
				""", UUID.class, requestId);
			mvc.perform(put("/api/v1/problem-studio/requests/{requestId}/selection", requestId)
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"itemIds\":[\"%s\",\"%s\"]}".formatted(selected.get(0), selected.get(1))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].selected").value(true));

			String firstSet = mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/save", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(2))
				.andReturn().getResponse().getContentAsString();
			String secondSet = mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/save", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertThat(objectMapper.readTree(firstSet).get("problemSetId").asText())
				.isEqualTo(objectMapper.readTree(secondSet).get("problemSetId").asText());

			String firstAssignment = mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/publish", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andReturn().getResponse().getContentAsString();
			String secondAssignment = mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/publish", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertThat(objectMapper.readTree(firstAssignment).get("assignmentId").asText())
				.isEqualTo(objectMapper.readTree(secondAssignment).get("assignmentId").asText());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM saved_problem_sets", Integer.class)).isOne();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_assignments", Integer.class)).isOne();

			mvc.perform(get("/api/v1/problem-studio/requests/{requestId}/printable", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.studentName").value("홍길동"))
				.andExpect(jsonPath("$.items.length()").value(2));
			mvc.perform(get("/api/v1/problem-studio/requests/{requestId}/review", requestId)
					.with(teacherAuthentication(OTHER_TEACHER)))
				.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("When 영역별 문항 수 합계가 AI 상한을 넘으면 Then 요청과 Outbox를 만들지 않는다")
		void rejectsMoreThanTwentyItems() throws Exception {
			mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-request-key-0002")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"studentId":"%s","targets":[
						 {"areaTag":"language","typeTag":"CONCEPT","count":12},
						 {"areaTag":"language","typeTag":"INFER","count":9}
						],"difficulty":"MEDIUM"}
						""".formatted(STUDENT)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_requests", Integer.class)).isZero();
		}

		@Test
		@DisplayName("When AI evidence가 없는 셀을 요청하면 Then 요청과 Outbox를 만들지 않는다")
		void rejectsCellWithoutAiEvidence() throws Exception {
			mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-request-key-0003")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"studentId":"%s","targets":[
						 {"areaTag":"language","typeTag":"FACT","count":1}
						],"difficulty":"MEDIUM"}
						""".formatted(STUDENT)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_requests", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_outbox", Integer.class)).isZero();
		}
	}

	@Test
	@DisplayName("Given 학생 계정일 때 When Problem Studio를 조회하면 Then 강사 전용 경계에서 거절한다")
	void rejectsStudentRoleAtSecurityBoundary() throws Exception {
		var principal = new AuthenticatedAccount(UUID.randomUUID(), AccountRole.STUDENT, null, UUID.randomUUID());
		mvc.perform(get("/api/v1/problem-studio/students")
				.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
					principal, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))))))
			.andExpect(status().isForbidden());
	}

	private void insertRoster() {
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'student-display-alias', 1, ?, ?)
			""", STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, '수능 국어 대비 반', '국어', 'ACTIVE', ?, ?)
			""", CLASS, TEACHER, time(), time());
		jdbc.update("""
			INSERT INTO class_enrollments
			    (class_group_id, teacher_id, student_id, status, enrolled_at, created_at)
			VALUES (?, ?, ?, 'ACTIVE', ?, ?)
			""", CLASS, TEACHER, STUDENT, time(), time());
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + TEACHER).getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO student_personal_information
			    (student_id, real_name, updated_by_account_id, updated_by_role, created_at, updated_at)
			VALUES (?, '홍길동', ?, 'TEACHER', ?, ?)
			""", STUDENT, accountId, time(), time());
	}

	private void insertLearning(String area, String type, int count, int correctCount) {
		for (int index = 0; index < count; index++) {
			jdbc.update("""
				INSERT INTO learning_records
				    (teacher_id, student_id, class_group_id, record_type, occurred_at,
				     source_type, correct, area_tag, type_tag, item_format, created_at, updated_at)
				VALUES (?, ?, ?, 'SOLVE', ?, 'studio-test', ?, ?, ?, 'mcq', ?, ?)
				""", TEACHER, STUDENT, CLASS, Instant.now().minusSeconds(86400).atOffset(ZoneOffset.UTC),
				index < correctCount, area, type, time(), time());
		}
	}

	private String successEvent(UUID requestId, String tenantAlias) {
		return """
			{
			  "event_id":"%s","event_type":"worker_job.succeeded",
			  "occurred_at":"%s","tenant_id":"%s","schema_version":"worker-job-1",
			  "correlation_id":"%s","payload":{
			    "worker_kind":"problem_generation","problem_request_id":"%s",
			    "job_id":"studio-job","execution_id":"studio-execution","set_id":"studio-set",
			    "result_status":"completed","result":{"problems":[
			      {"id":"p1","stem":"음운 변동 유형을 고르세요.","choices":[{"no":1,"text":"오답1","why_wrong":"근거 불일치"},{"no":2,"text":"오답2","why_wrong":"근거 불일치"},{"no":3,"text":"정답","why_wrong":null}],"answer":{"correct_no":3},"source_basis":"최근 오답 영역","validation_status":"verified"},
			      {"id":"p2","question":"문학 표현법을 고르세요.","choices":[{"no":1,"text":"정답","why_wrong":null},{"no":2,"text":"오답","why_wrong":"근거 불일치"}],"answer":{"correct_no":1},"generation_basis":"추론형 약점","validation_status":"needs_review","validation_message":"문제 의도 확인 필요"},
			      {"id":"p3","stem":"검증 불가 문항","options":["정답","오답"],"answer":{"correct_no":1},"validation_status":"verification_unavailable"},
			      {"id":"p4","stem":"제외 문항","options":["정답","오답"],"answer":{"correct_no":1},"validation_status":"dropped","exclusion_reason":"생성 실패"}
			    ]},"versions":{"model":"m2-v2"}
			  }
			}
			""".formatted(UUID.randomUUID(), Instant.now().plusSeconds(60), tenantAlias, requestId, requestId);
	}

	private String childSuccessEvent(UUID requestId, UUID executionId, int targetIndex, String tenantAlias) {
		return successEvent(requestId, tenantAlias).replace(
			"\"worker_kind\":\"problem_generation\",\"problem_request_id\":\"%s\",".formatted(requestId),
			"\"worker_kind\":\"problem_generation\",\"problem_request_id\":\"%s\",\"problem_execution_id\":\"%s\",\"target_index\":%d,\"adapter_execution_id\":\"%s\",".formatted(
				requestId, executionId, targetIndex, UUID.randomUUID()));
	}

	private String childFailedEvent(UUID requestId, UUID executionId, int targetIndex, String tenantAlias) {
		return """
			{"event_id":"%s","event_type":"worker_job.failed","occurred_at":"%s",
			 "tenant_id":"%s","schema_version":"worker-job-1","correlation_id":"%s","payload":{
			 "worker_kind":"problem_generation","problem_request_id":"%s","problem_execution_id":"%s",
			 "target_index":%d,"adapter_execution_id":"%s","job_id":"failed-job","execution_id":"failed-ai-execution",
			 "result_status":"failed","error_code":"AI_EXECUTION_FAILED"}}
			""".formatted(UUID.randomUUID(),Instant.now().plusSeconds(60),tenantAlias,requestId,requestId,
				executionId,targetIndex,UUID.randomUUID());
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
		var principal = new AuthenticatedAccount(accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
	}

	private static java.time.OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}
}
