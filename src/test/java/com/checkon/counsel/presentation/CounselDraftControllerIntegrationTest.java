package com.checkon.counsel.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.domain.CounselDraftStatus;
import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("상담 초안 프론트엔드 API")
class CounselDraftControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f900-0000-7000-8000-000000000801");
	private static final UUID STUDENT = UUID.fromString("0198f900-0000-7000-8000-000000000811");
	private static final UUID CLASS_GROUP = UUID.fromString("0198f900-0000-7000-8000-000000000821");
	private static final UUID OTHER_STUDENT = UUID.fromString("0198f900-0000-7000-8000-000000000812");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-20T00:00:00Z");
	private static final String STUDENT_REAL_NAME = "박서연";

	@MockitoBean
	private CounselClient client;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM counsel_draft_kafka_outbox_events");
		jdbc.update("DELETE FROM counsel_draft_jobs");
		jdbc.update("DELETE FROM counsel_inquiries");
		jdbc.update("DELETE FROM ai_guardian_aliases");
		jdbc.update("DELETE FROM ai_class_aliases");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM student_personal_information");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");

		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		insertStudentAndRelationship(STUDENT, TEACHER, STUDENT_REAL_NAME);
		insertClassGroup(CLASS_GROUP, TEACHER);
	}

	@Nested
	@DisplayName("Given 상담 초안 생성을 요청할 때")
	class GivenCreatingADraft {

		@Test
		@DisplayName("When 요청이 유효하면 Then 202와 즉시 minting된 job_id를 반환하고 학생 실명은 마스킹해 카프카로 발행한다")
		void createsADraftWithMaskedText() throws Exception {
			var result = mvc.perform(post("/api/v1/counsel/drafts")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "iq_884")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(STUDENT, CLASS_GROUP, STUDENT_REAL_NAME + "이가 요즘 힘들어해요")))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.jobId").exists())
				.andExpect(jsonPath("$.status").value("queued"))
				.andReturn();
			String jobId = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("jobId").asText();

			assertThat(jdbc.queryForObject(
				"SELECT student_ref FROM counsel_draft_jobs WHERE job_id = ?", String.class, jobId
			)).matches("st_[0-9a-f]{32}");
			assertThat(jdbc.queryForObject(
				"SELECT parent_ref FROM counsel_draft_jobs WHERE job_id = ?", String.class, jobId
			)).matches("pa_[0-9a-f]{32}");
			assertThat(jdbc.queryForObject(
				"SELECT class_ref FROM counsel_draft_jobs WHERE job_id = ?", String.class, jobId
			)).matches("cl_[0-9a-f]{32}");

			String payload = jdbc.queryForObject("""
				SELECT o.payload FROM counsel_draft_kafka_outbox_events o
				JOIN counsel_draft_jobs j ON j.id = o.counsel_draft_job_id
				WHERE j.job_id = ?
				""", String.class, jobId);
			JsonNode request = objectMapper.readTree(payload).get("payload");
			String textMasked = request.get("inquiry").get("text_masked").asText();
			assertThat(textMasked).doesNotContain(STUDENT_REAL_NAME).contains("○○");

			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM counsel_draft_jobs WHERE teacher_id = ?", Integer.class, TEACHER
			)).isEqualTo(1);
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When Idempotency-Key 헤더가 없으면 Then 저장 없이 요청을 거절한다")
		void rejectsARequestWithoutIdempotencyKey() throws Exception {
			mvc.perform(post("/api/v1/counsel/drafts")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(STUDENT, CLASS_GROUP, "문의합니다")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("When 다른 강사의 학생을 지정하면 Then 존재를 숨겨 404를 반환한다")
		void hidesAnotherTeachersStudent() throws Exception {
			mvc.perform(post("/api/v1/counsel/drafts")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "iq_885")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(OTHER_STUDENT, CLASS_GROUP, "문의합니다")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COUNSEL_TARGET_NOT_FOUND"));
		}

		@Test
		@DisplayName("When 같은 Idempotency-Key에 다른 본문이 오면 Then 409를 반환한다")
		void mapsIdempotencyConflict() throws Exception {
			mvc.perform(post("/api/v1/counsel/drafts")
				.with(teacherAuthentication(TEACHER))
				.header("Idempotency-Key", "iq_886")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(STUDENT, CLASS_GROUP, "문의합니다")))
				.andExpect(status().isAccepted());

			mvc.perform(post("/api/v1/counsel/drafts")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "iq_886")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(STUDENT, CLASS_GROUP, "다른 본문입니다")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		}
	}

	@Nested
	@DisplayName("Given 초안 결과를 조회할 때")
	class GivenFetchingADraft {

		@Test
		@DisplayName("When AI가 근거 부족으로 초안을 거부했으면 Then 정상 결과로 그대로 전달한다")
		void returnsTheDraftResult() throws Exception {
			var created = mvc.perform(post("/api/v1/counsel/drafts")
				.with(teacherAuthentication(TEACHER))
				.header("Idempotency-Key", "iq_1029")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(STUDENT, CLASS_GROUP, "문의합니다")))
				.andExpect(status().isAccepted())
				.andReturn();
			String jobId = objectMapper.readTree(created.getResponse().getContentAsByteArray()).get("jobId").asText();
			jdbc.update("UPDATE counsel_draft_jobs SET ai_job_id = ? WHERE job_id = ?", "cj_1029", jobId);
			when(client.getDraft(eq("cj_1029"), any(), any()))
				.thenReturn(rejectedInsufficientResponse());

			mvc.perform(get("/api/v1/counsel/drafts/{jobId}", jobId).with(teacherAuthentication(TEACHER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("succeeded"))
				.andExpect(jsonPath("$.result.draftStatus").value("rejected_insufficient"))
				.andExpect(jsonPath("$.result.statusReason").value("no_citable_evidence"));
		}
	}

	@Nested
	@DisplayName("Given 초안을 다듬을 때")
	class GivenRefiningADraft {

		@Test
		@DisplayName("When Idempotency-Key 헤더가 없으면 Then 400을 반환한다")
		void rejectsARefineWithoutIdempotencyKey() throws Exception {
			mvc.perform(post("/api/v1/counsel/drafts/{jobId}/refine", "cj_1029")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "instruction": "조금 더 부드럽게 써 주세요.", "turnNo": 1 }
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Nested
	@DisplayName("Given 강사가 발송을 완료했을 때")
	class GivenMarkingADraftAsSent {

		@Test
		@DisplayName("When 발송본을 기록하면 Then 204를 반환하고 로컬 잡에 남긴다")
		void recordsTheSentText() throws Exception {
			var created = mvc.perform(post("/api/v1/counsel/drafts")
				.with(teacherAuthentication(TEACHER))
				.header("Idempotency-Key", "iq_890")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(STUDENT, CLASS_GROUP, "문의합니다")))
				.andExpect(status().isAccepted())
				.andReturn();
			String jobId = objectMapper.readTree(created.getResponse().getContentAsByteArray()).get("jobId").asText();

			mvc.perform(post("/api/v1/counsel/drafts/{jobId}/sent", jobId)
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "text": "어머님, 실제로 발송한 문구입니다." }
						"""))
				.andExpect(status().isNoContent());

			assertThat(jdbc.queryForObject(
				"SELECT sent_text FROM counsel_draft_jobs WHERE job_id = ?",
				String.class, jobId
			)).isEqualTo("어머님, 실제로 발송한 문구입니다.");
		}

		@Test
		@DisplayName("When 존재하지 않는 job이면 Then 404를 반환한다")
		void rejectsAnUnknownJob() throws Exception {
			mvc.perform(post("/api/v1/counsel/drafts/{jobId}/sent", "missing-job")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "text": "발송 본문" }
						"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COUNSEL_JOB_NOT_FOUND"));
		}
	}

	private String requestBody(UUID studentId, UUID classId, String text) {
		return """
			{
			  "studentId": "%s",
			  "classId": "%s",
			  "inquiryRef": "iq_884",
			  "topic": "grade",
			  "urgency": "immediate",
			  "receivedAt": "2026-08-20T14:20:00+09:00",
			  "text": "%s",
			  "labels": ["narrative", "anxious"],
			  "dismissedSuggestions": [],
			  "periodLabel": "2026년 8월",
			  "facts": [ { "recordId": "le_2041", "summary": "6월 지문 42개·312문항" } ]
			}
			""".formatted(studentId, classId, text);
	}

	private static CounselDraftGetResponse rejectedInsufficientResponse() {
		return new CounselDraftGetResponse(
			new CounselDraftGetResponse.Data(
				"cj_1029", CounselJobPhase.SUCCEEDED,
				new CounselDraftGetResponse.Result(
					CounselDraftStatus.REJECTED_INSUFFICIENT, null, List.of(), List.of(), List.of(),
					"no_citable_evidence", java.time.OffsetDateTime.now()
				)
			),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
	}

	private static CounselMeta.Versions versions() {
		return new CounselMeta.Versions("0.1.0", "counsel-pack-0.1", null, "0.3", "0.1", "0.1", null, null, null, null);
	}

	private void insertStudentAndRelationship(UUID studentId, UUID teacherId, String realName) {
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, "student-" + studentId, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", teacherId, studentId, time(), time());
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO student_personal_information
			    (student_id, real_name, updated_by_account_id, updated_by_role, created_at, updated_at)
			VALUES (?, ?, ?, 'TEACHER', ?, ?)
			""", studentId, realName, accountId, time(), time());
	}

	private void insertClassGroup(UUID classId, UUID teacherId) {
		jdbc.update("""
			INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, '문법반', '국어', 'ACTIVE', ?, ?)
			""", classId, teacherId, time(), time());
	}

	private static java.time.OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
		var principal = new AuthenticatedAccount(accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}
}
