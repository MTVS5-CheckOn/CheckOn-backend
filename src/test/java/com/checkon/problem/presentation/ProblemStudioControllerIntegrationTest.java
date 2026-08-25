package com.checkon.problem.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.problem.application.ProblemAssignmentResponseService;
import com.checkon.problem.integration.kafka.ProblemGenerationResultEventHandler;
import com.checkon.problem.integration.ai.ProblemDiagnosisClient;
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
	@Autowired ProblemAssignmentResponseService responseService;
	@MockitoBean ProblemDiagnosisClient diagnosisClient;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM problem_assignment_responses");
		jdbc.update("DELETE FROM problem_generation_outbox");
		jdbc.update("DELETE FROM problem_generation_revision_requests");
		jdbc.update("DELETE FROM problem_assignments");
		jdbc.update("DELETE FROM saved_problem_set_items");
		jdbc.update("DELETE FROM saved_problem_sets");
		jdbc.update("DELETE FROM problem_generation_item_options");
		jdbc.update("DELETE FROM problem_generation_slots");
		jdbc.update("DELETE FROM problem_generation_items");
		jdbc.update("DELETE FROM problem_generation_consumed_events");
		jdbc.update("DELETE FROM problem_generation_executions");
		jdbc.update("DELETE FROM problem_generation_request_targets");
		jdbc.update("DELETE FROM problem_generation_requests");
		jdbc.update("DELETE FROM problem_diagnosis_snapshots");
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
		when(diagnosisClient.diagnose(any(),any())).thenAnswer(invocation->diagnosisResponse(invocation.getArgument(0)));
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
				.andExpect(jsonPath("$.metadata.pageNumber").value(0))
				.andExpect(jsonPath("$.metadata.pageSize").value(20))
				.andExpect(jsonPath("$.metadata.itemCount").value(1))
				.andExpect(jsonPath("$.metadata.totalItemCount").value(1))
				.andExpect(jsonPath("$.metadata.totalPageCount").value(1))
				.andExpect(jsonPath("$.metadata.isFirst").value(true))
				.andExpect(jsonPath("$.metadata.isLast").value(true))
				.andExpect(jsonPath("$.items[0].studentId").value(STUDENT.toString()))
				.andExpect(jsonPath("$.items[0].studentName").value("홍길동"))
				.andExpect(jsonPath("$.items[0].className").value("수능 국어 대비 반"))
				.andExpect(jsonPath("$.items[0].subject").value("국어"))
				.andExpect(jsonPath("$.items[0].recentSignalCount").value(0));

			mvc.perform(get("/api/v1/problem-studio/students/{studentId}/weakness-analysis", STUDENT)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.minimumSampleSize").value(10))
				.andExpect(jsonPath("$.generationCapabilities.length()").value(20))
				.andExpect(jsonPath("$.skillNodes[?(@.skillNodeId == 'node.concept')].verdict").value("suspect"))
				.andExpect(jsonPath("$.generationCapabilities[?(@.areaTag == 'language' && @.typeTag == 'CONCEPT')].recommendedMaximumCount")
					.value(3))
				.andExpect(jsonPath("$.generationCapabilities[?(@.areaTag == 'language' && @.typeTag == 'INFER')].maximumCount")
					.value(20))
				.andExpect(jsonPath("$.diagnosis.status").value("GENERATED"))
				.andExpect(jsonPath("$.diagnosis.taxonomyVersion").value("v1"))
				.andExpect(jsonPath("$.cells[?(@.areaTag == 'language' && @.typeTag == 'CONCEPT')].evaluation").value("GOOD"))
				.andExpect(jsonPath("$.cells[?(@.areaTag == 'language' && @.typeTag == 'INFER')].evaluation").value("WEAK_SIGNAL"));
		}
	}

	@Nested
	@DisplayName("Given 프론트 영역별 출제 조건이 있을 때")
	class GivenFrontendGenerationTargets {
		@Test
		@DisplayName("When Step 2부터 발행·오답 저장까지 수행하면 Then 선택 오답이 다음 진단에 환류된다")
		void completesReviewSaveAndPublishIdempotently() throws Exception {
			UUID diagnosisId=diagnose();
			MvcResult created = mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-request-key-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "studentId":"%s",
						  "diagnosisId":"%s",
						  "targets":[
						    {"areaTag":"LANGUAGE","typeTag":"CONCEPT","count":3,"skillNodeId":"node.concept"},
						    {"areaTag":"language","typeTag":"INFER","count":2,"skillNodeId":"node.infer"}
						  ],
						  "difficulty":"LOW"
						}
						""".formatted(STUDENT,diagnosisId)))
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
			assertThat(childPayloads).satisfiesExactly(
				payload -> assertThat(objectMapper.readTree(payload).at("/payload/request/manual_targets").size()).isEqualTo(1),
				payload -> assertThat(objectMapper.readTree(payload).at("/payload/request/manual_targets").size()).isEqualTo(1)
			);
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
			UUID assignmentId=UUID.fromString(objectMapper.readTree(firstAssignment).get("assignmentId").asText());
			var response=responseService.record(TEACHER,STUDENT,assignmentId,selected.getFirst(),1,
				FIXTURE_TIME.plusSeconds(3600));
			assertThat(response.correct()).isFalse();
			assertThat(response.correctNo()).isEqualTo(3);
			assertThat(response.misconceptionTag()).isEqualTo("tag_1");
			mvc.perform(get("/api/v1/problem-studio/students/{studentId}/weakness-analysis",STUDENT)
					.with(teacherAuthentication(TEACHER))).andExpect(status().isOk());
			String diagnosisRequest=jdbc.queryForObject("""
				SELECT request_payload::text FROM problem_diagnosis_snapshots
				WHERE teacher_id=? AND student_id=? ORDER BY diagnosed_at DESC,id DESC LIMIT 1
				""",String.class,TEACHER,STUDENT);
			assertThat(diagnosisRequest).contains("\"chosen_no\": 1","\"correct_no\": 3",
				"\"misconception_tag\": \"tag_1\"","\"skill_node_id\": \"node.concept\"");
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
						{"studentId":"%s","diagnosisId":"%s","targets":[
						 {"areaTag":"language","typeTag":"CONCEPT","count":12},
						 {"areaTag":"language","typeTag":"INFER","count":9}
						],"difficulty":"MEDIUM"}
						""".formatted(STUDENT,UUID.randomUUID())))
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
						{"studentId":"%s","diagnosisId":"%s","targets":[
						 {"areaTag":"language","typeTag":"FACT","count":1}
						],"difficulty":"MEDIUM"}
						""".formatted(STUDENT,UUID.randomUUID())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_requests", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_outbox", Integer.class)).isZero();
		}

		@Test
		@DisplayName("When 강사가 한 cell의 node를 선택하면 Then 같은 node로 여러 문항을 요청한다")
		void reusesTheTeacherSelectedNodeForTheRequestedCount() throws Exception {
			UUID diagnosisId = diagnose();

			MvcResult result = mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-request-key-insufficient")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"studentId":"%s","diagnosisId":"%s","targets":[
						 {"areaTag":"language","typeTag":"INFER","count":3,"skillNodeId":"node.infer"}
						],"difficulty":"MEDIUM"}
						""".formatted(STUDENT, diagnosisId)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn();
			UUID requestId = UUID.fromString(objectMapper.readTree(
				result.getResponse().getContentAsString()).get("requestId").asText());

			assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM problem_generation_executions
				WHERE problem_request_id = ? AND status = 'QUEUED'
				""", Integer.class, requestId)).isEqualTo(1);
			assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM problem_generation_outbox WHERE problem_request_id = ?
				""", Integer.class, requestId)).isEqualTo(1);
		}

		@Test
		@DisplayName("When terminal 참조 뒤 slot 상세가 나뉘어 도착하면 Then 마지막 slot까지 processing을 유지하고 부분 결과를 복원한다")
		void completesOnlyAfterEveryReferencedSlotArrives() throws Exception {
			UUID diagnosisId = diagnose();
			MvcResult created = mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-slot-events-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"studentId":"%s","diagnosisId":"%s","targets":[
						 {"areaTag":"language","typeTag":"CONCEPT","count":2,"skillNodeId":"node.concept"}
						],"difficulty":"MEDIUM"}
						""".formatted(STUDENT, diagnosisId)))
				.andExpect(status().isAccepted()).andReturn();
			UUID requestId = UUID.fromString(objectMapper.readTree(
				created.getResponse().getContentAsString()).get("requestId").asText());
			String tenantAlias = jdbc.queryForObject(
				"SELECT tenant_alias FROM problem_generation_requests WHERE id=?", String.class, requestId);
			UUID executionId = jdbc.queryForObject(
				"SELECT id FROM problem_generation_executions WHERE problem_request_id=?", UUID.class, requestId);

			resultHandler.handle(childProgressEvent(requestId, executionId, tenantAlias, "paused"));
			assertThat(jdbc.queryForObject(
				"SELECT worker_phase FROM problem_generation_executions WHERE id=?", String.class, executionId))
				.isEqualTo("paused");

			resultHandler.handle(childTerminalReferenceEvent(requestId, executionId, tenantAlias, 2));
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_executions WHERE id=?", String.class, executionId))
				.isEqualTo("RUNNING");

			resultHandler.handle(slotDetailEvent(requestId, executionId, tenantAlias, 0, false));
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_executions WHERE id=?", String.class, executionId))
				.isEqualTo("RUNNING");

			resultHandler.handle(slotDetailEvent(requestId, executionId, tenantAlias, 1, true));
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_executions WHERE id=?", String.class, executionId))
				.isEqualTo("SUCCEEDED");
			assertThat(jdbc.queryForObject(
				"SELECT received_slot_count FROM problem_generation_executions WHERE id=?", Integer.class, executionId))
				.isEqualTo(2);

			mvc.perform(get("/api/v1/problem-studio/requests/{requestId}/review", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestStatus").value("SUCCEEDED"))
				.andExpect(jsonPath("$.counts.passed").value(1))
				.andExpect(jsonPath("$.counts.excluded").value(1))
				.andExpect(jsonPath("$.items[0].correctAnswerText").value("정답"));

			MvcResult revision=mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/executions/{executionId}/slots/0/revisions",
					requestId,executionId).with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key","studio-revision-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"baseRevisionNo":0,"revisionKind":"ai_refine","instruction":"문두를 더 명확하게 수정"}
						"""))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", "/api/v1/problem-studio/requests/" + requestId + "/review"))
				.andExpect(jsonPath("$.replayed").value(false)).andReturn();
			UUID revisionId=UUID.fromString(objectMapper.readTree(
				revision.getResponse().getContentAsString()).get("revisionRequestId").asText());
			mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/executions/{executionId}/slots/0/revisions",
					requestId,executionId).with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key","studio-revision-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"baseRevisionNo":0,"revisionKind":"ai_refine","instruction":"문두를 더 명확하게 수정"}
						"""))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", "/api/v1/problem-studio/requests/" + requestId + "/review"))
				.andExpect(jsonPath("$.replayed").value(true));
			assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM problem_generation_outbox WHERE revision_request_id=?
				""",Integer.class,revisionId)).isOne();

			resultHandler.handle(revisionSucceededEvent(requestId,executionId,revisionId,tenantAlias));
			mvc.perform(get("/api/v1/problem-studio/requests/{requestId}/review", requestId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.slots[0].currentRevisionNo").value(1))
				.andExpect(jsonPath("$.items[0].stem").value("수정된 문두입니다."));
			mvc.perform(post("/api/v1/problem-studio/requests/{requestId}/executions/{executionId}/slots/0/revisions",
					requestId,executionId).with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key","studio-revision-stale-0002")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"baseRevisionNo":0,"revisionKind":"ai_refine","instruction":"오래된 버전 수정"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
				.andExpect(jsonPath("$.detail.reason").value("stale_base_revision"))
				.andExpect(jsonPath("$.detail.currentRevisionNo").value(1));
		}

		@Test
		@DisplayName("When 독서 영역 자료를 요청하면 Then passage를 AI child snapshot에 정확히 고정한다")
		void storesTheReadingPassageContractWithoutInventedDefaults() throws Exception {
			UUID diagnosisId = diagnose();
			MvcResult created = mvc.perform(post("/api/v1/problem-studio/requests")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "studio-reading-source-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"studentId":"%s","diagnosisId":"%s","targets":[{
						 "areaTag":"reading","typeTag":"FACT","count":1,"skillNodeId":"reading.fact.node",
						 "passage":{"areaTag":"reading","domain":"science","topicHint":"기후 기술",
						 "wordCount":900,"sentenceComplexity":"standard","paragraphCount":4,
						 "bannedTopicsVersion":"pg-banned-v1"}
						}],"difficulty":"HIGH"}
						""".formatted(STUDENT, diagnosisId)))
				.andExpect(status().isAccepted()).andReturn();
			UUID requestId = UUID.fromString(objectMapper.readTree(
				created.getResponse().getContentAsString()).get("requestId").asText());
			String payload = jdbc.queryForObject("""
				SELECT request_snapshot::text FROM problem_generation_executions
				WHERE problem_request_id=?
				""", String.class, requestId);
			var request = objectMapper.readTree(payload);
			assertThat(request.at("/manual_targets/0").asText()).isEqualTo("reading.fact.node");
			assertThat(request.at("/passage/domain").asText()).isEqualTo("science");
			assertThat(request.at("/passage/banned_topics_version").asText()).isEqualTo("pg-banned-v1");
			assertThat(request.has("work_selection")).isFalse();
			assertThat(request.has("graph_version")).isFalse();
			assertThat(request.has("config_version")).isFalse();
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
			      {"id":"p1","stem":"음운 변동 유형을 고르세요.","choices":[{"no":1,"text":"오답1"},{"no":2,"text":"오답2"},{"no":3,"text":"정답"},{"no":4,"text":"오답4"},{"no":5,"text":"오답5"}],"answer":{"correct_no":3},"source_basis":"최근 오답 영역","validation_status":"verified"},
			      {"id":"p2","question":"문학 표현법을 고르세요.","choices":[{"no":1,"text":"정답"},{"no":2,"text":"오답2"},{"no":3,"text":"오답3"},{"no":4,"text":"오답4"},{"no":5,"text":"오답5"}],"answer":{"correct_no":1},"generation_basis":"추론형 약점","validation_status":"needs_review","validation_message":"문제 의도 확인 필요"},
			      {"id":"p3","stem":"검증 불가 문항","options":["정답","오답2","오답3","오답4","오답5"],"answer":{"correct_no":1},"validation_status":"verification_unavailable"},
			      {"id":"p4","stem":"제외 문항","options":["정답","오답2","오답3","오답4","오답5"],"answer":{"correct_no":1},"validation_status":"dropped","exclusion_reason":"생성 실패"}
			    ]},"versions":{"model":"m2-v2"}
			  }
			}
			""".formatted(UUID.randomUUID(), Instant.now().plusSeconds(60), tenantAlias, requestId, requestId);
	}

	private String childSuccessEvent(UUID requestId, UUID executionId, int targetIndex, String tenantAlias) {
		return """
			{"event_id":"%s","event_type":"worker_job.succeeded","occurred_at":"%s","tenant_id":"%s",
			 "schema_version":"worker-job-1","correlation_id":"%s","payload":{"worker_kind":"problem_generation",
			 "problem_request_id":"%s","problem_execution_id":"%s","target_index":%d,"adapter_execution_id":"%s",
			 "job_id":"studio-job","execution_id":"studio-execution","set_id":"studio-set","result_status":"completed",
			 "result":{"set_id":"studio-set","requested_count":4,"processed_count":4,
			 "status_counts":{"verified":1,"needs_review":1,"verification_unavailable":1,"dropped":1},"items":[
			 {"slot_index":0,"item_id":"p1","status":"verified","current_revision_no":0,"available_actions":["refine"],"item":{"area_tag":"language","type_tag":"concept","skill_node_id":"node.concept","stem":"음운 변동 유형을 고르세요.","choices":[{"no":1,"text":"오답1","why_wrong":"근거","misconception_tag":"tag_1"},{"no":2,"text":"오답2","why_wrong":"근거","misconception_tag":"tag_2"},{"no":3,"text":"정답","why_wrong":null,"misconception_tag":null},{"no":4,"text":"오답4","why_wrong":"근거","misconception_tag":"tag_4"},{"no":5,"text":"오답5","why_wrong":"근거","misconception_tag":"tag_5"}],"answer":{"correct_no":3},"evidence":[{"kind":"rule","ref":"grammar:1"}]}},
			 {"slot_index":1,"item_id":"p2","status":"needs_review","current_revision_no":0,"available_actions":["refine"],"review_reason":"manual_target_first","item":{"skill_node_id":"node.concept","stem":"문학 표현법을 고르세요.","choices":[{"no":1,"text":"정답"},{"no":2,"text":"오답2"},{"no":3,"text":"오답3"},{"no":4,"text":"오답4"},{"no":5,"text":"오답5"}],"answer":{"correct_no":1}}},
			 {"slot_index":2,"item_id":"p3","status":"verification_unavailable","current_revision_no":0,"available_actions":[],"item":{"skill_node_id":"node.concept","stem":"검증 불가 문항","choices":[{"no":1,"text":"정답"},{"no":2,"text":"오답2"},{"no":3,"text":"오답3"},{"no":4,"text":"오답4"},{"no":5,"text":"오답5"}],"answer":{"correct_no":1}}},
			 {"slot_index":3,"item_id":null,"status":"dropped","current_revision_no":0,"failure_reason":"generation_exhausted","item":null}]},
			 "versions":{"contract":"0.1"}}}
			""".formatted(UUID.randomUUID(),Instant.now().plusSeconds(60),tenantAlias,requestId,requestId,
				executionId,targetIndex,UUID.randomUUID());
	}

	private UUID diagnose() throws Exception {
		String body=mvc.perform(get("/api/v1/problem-studio/students/{studentId}/weakness-analysis",STUDENT)
			.with(teacherAuthentication(TEACHER))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(body).get("diagnosisId").asText());
	}
	private String diagnosisResponse(String request) throws Exception { String hash=objectMapper.readTree(request).get("snapshot_hash").asText(); return """
		{"data":{"status":"generated","status_reason":null,"weakness_map":{"graph_version":"graph-v1","taxonomy_version":"v1",
		"config_version":"config-v1","snapshot_hash":"%s",
		"nodes":{"node.concept":{"verdict":"suspect","basis":["cell:language×concept"]},"node.infer":{"verdict":"suspect","basis":["cell:language×infer"]},
		"reading.fact.node":{"verdict":"suspect","basis":["cell:reading×fact"]}},
		"propagated":{"concept.root.1":{"score":3.0,"from_nodes":["node.concept"]},
		"concept.root.2":{"score":2.0,"from_nodes":["node.concept"]},"concept.root.3":{"score":1.0,"from_nodes":["node.concept"]},
		"infer.root.1":{"score":3.0,"from_nodes":["node.infer"]},"infer.root.2":{"score":2.0,"from_nodes":["node.infer"]}}},
		"grid":{"cell_min_items":10,"cells":[{"area_tag":"language","type_tag":"concept","acc":0.8,"n":10,"verdict":"ok"},
		{"area_tag":"language","type_tag":"infer","acc":0.2,"n":10,"verdict":"weak"}]}}}
		""".formatted(hash); }

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

	private String childProgressEvent(UUID requestId, UUID executionId, String tenantAlias, String phase) {
		return """
			{"event_id":"%s","event_type":"worker_job.progress","occurred_at":"%s",
			 "tenant_id":"%s","schema_version":"pg-result-reference-1","correlation_id":"%s","payload":{
			 "worker_kind":"problem_generation","problem_request_id":"%s","problem_execution_id":"%s",
			 "target_index":0,"worker_phase":"%s","job_id":"slot-job","execution_id":"slot-execution"}}
			""".formatted(UUID.randomUUID(),Instant.now().plusSeconds(60),tenantAlias,requestId,
			requestId,executionId,phase);
	}

	private String childTerminalReferenceEvent(UUID requestId, UUID executionId, String tenantAlias, int requested) {
		return """
			{"event_id":"%s","event_type":"worker_job.succeeded","occurred_at":"%s",
			 "tenant_id":"%s","schema_version":"pg-result-reference-1","correlation_id":"%s","payload":{
			 "worker_kind":"problem_generation","problem_request_id":"%s","problem_execution_id":"%s",
			 "target_index":0,"worker_phase":"succeeded","domain_status":"partial_success",
			 "job_id":"slot-job","execution_id":"slot-execution","set_id":"slot-set",
			 "requested_count":%d,"processed_count":%d,"unstarted_count":0,
			 "status_counts":{"verified":1,"dropped":1},"result_ref":"problem-set:slot-set"}}
			""".formatted(UUID.randomUUID(),Instant.now().plusSeconds(61),tenantAlias,requestId,
			requestId,executionId,requested,requested);
	}

	private String slotDetailEvent(UUID requestId, UUID executionId, String tenantAlias, int slotIndex,
		boolean dropped) {
		String slot = dropped ? """
			{"slot_index":1,"item_id":null,"status":"dropped","current_revision_no":0,
			 "available_actions":[],"failure_reason":"generation_exhausted","failure_detail":{"attempts":4},"item":null}
			""" : """
			{"slot_index":0,"item_id":"slot-item-1","status":"verified","current_revision_no":0,
			 "available_actions":["refine"],"revisions":[],"item":{"area_tag":"language","type_tag":"concept","skill_node_id":"node.concept",
			 "stem":"정답을 고르세요.","choices":[
			 {"no":1,"text":"오답1","why_wrong":"개념 혼동","misconception_tag":"concept_confusion"},
			 {"no":2,"text":"정답","why_wrong":null,"misconception_tag":null},
			 {"no":3,"text":"오답3","why_wrong":"대상 혼동","misconception_tag":"target_confusion"},
			 {"no":4,"text":"오답4","why_wrong":"범위 혼동","misconception_tag":"range_confusion"},
			 {"no":5,"text":"오답5","why_wrong":"조건 혼동","misconception_tag":"condition_confusion"}],
			 "answer":{"correct_no":2},"rationale":"개념 근거","verification":{"release_decision":"verified"}}}
			""";
		return """
			{"event_id":"%s","event_type":"problem_generation.slot.detail","occurred_at":"%s",
			 "tenant_id":"%s","schema_version":"pg-slot-detail-1","correlation_id":"%s","payload":{
			 "problem_request_id":"%s","problem_execution_id":"%s","target_index":0,
			 "job_id":"slot-job","execution_id":"slot-execution","set_id":"slot-set","slot":%s}}
			""".formatted(UUID.randomUUID(),Instant.now().plusSeconds(62+slotIndex),tenantAlias,
			requestId,requestId,executionId,slot);
	}

	private String revisionSucceededEvent(UUID requestId,UUID executionId,UUID revisionId,String tenantAlias) {
		return """
			{"event_id":"%s","event_type":"problem_generation.revision.succeeded","occurred_at":"%s",
			 "tenant_id":"%s","schema_version":"pg-revision-result-1","correlation_id":"%s","payload":{
			 "problem_request_id":"%s","problem_execution_id":"%s","revision_request_id":"%s",
			 "target_index":0,"execution_id":"revision-ai-execution","set_id":"slot-set","slot":{
			 "slot_index":0,"item_id":"slot-item-1","status":"verified","current_revision_no":1,
			 "available_actions":["refine"],"revisions":[{"revision_no":1}],"item":{"area_tag":"language","type_tag":"concept","skill_node_id":"node.concept",
			 "stem":"수정된 문두입니다.","choices":[
			 {"no":1,"text":"오답1","why_wrong":"개념 혼동","misconception_tag":"concept_confusion"},
			 {"no":2,"text":"정답","why_wrong":null,"misconception_tag":null},
			 {"no":3,"text":"오답3","why_wrong":"대상 혼동","misconception_tag":"target_confusion"},
			 {"no":4,"text":"오답4","why_wrong":"범위 혼동","misconception_tag":"range_confusion"},
			 {"no":5,"text":"오답5","why_wrong":"조건 혼동","misconception_tag":"condition_confusion"}],
			 "answer":{"correct_no":2},"rationale":"수정 근거"}}}}
			""".formatted(UUID.randomUUID(),Instant.now().plusSeconds(70),tenantAlias,requestId,
			requestId,executionId,revisionId);
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
