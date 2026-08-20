package com.checkon.counsel.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
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
import com.checkon.counsel.integration.ai.CounselClientException;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.support.RosterTestFixture;

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

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM counsel_draft_jobs");
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
		@DisplayName("When AI가 정상 응답하면 Then 202와 job 정보를 반환하고 학생 실명은 마스킹해 AI로 보낸다")
		void createsADraftWithMaskedText() throws Exception {
			ArgumentCaptor<CounselDraftCreateRequest> captor = ArgumentCaptor.forClass(CounselDraftCreateRequest.class);
			when(client.createDraft(captor.capture(), any())).thenReturn(succeededResponse());

			mvc.perform(post("/api/v1/counsel/drafts")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "iq_884")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(STUDENT, CLASS_GROUP, STUDENT_REAL_NAME + "이가 요즘 힘들어해요")))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.jobId").value("019846dc-7c00-7000-8000-0000000006a1"))
				.andExpect(jsonPath("$.status").value("succeeded"));

			CounselDraftCreateRequest sent = captor.getValue();
			assertThat(sent.inquiry().textMasked()).doesNotContain(STUDENT_REAL_NAME).contains("○○");
			assertThat(sent.studentRef()).matches("st_[0-9a-f]{32}");
			assertThat(sent.parentRef()).matches("pa_[0-9a-f]{32}");
			assertThat(sent.classRef()).matches("cl_[0-9a-f]{32}");

			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM counsel_draft_jobs WHERE teacher_id = ?", Integer.class, TEACHER
			)).isEqualTo(1);
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
		@DisplayName("When AI가 멱등 충돌을 반환하면 Then 409를 반환한다")
		void mapsIdempotencyConflict() throws Exception {
			when(client.createDraft(any(), any()))
				.thenThrow(CounselClientException.idempotencyConflict(null, null));

			mvc.perform(post("/api/v1/counsel/drafts")
					.with(teacherAuthentication(TEACHER))
					.header("Idempotency-Key", "iq_886")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody(STUDENT, CLASS_GROUP, "문의합니다")))
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
			when(client.getDraft(eq("cj_1029"), any(), any()))
				.thenReturn(rejectedInsufficientResponse());

			mvc.perform(get("/api/v1/counsel/drafts/{jobId}", "cj_1029").with(teacherAuthentication(TEACHER)))
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

	private static CounselDraftCreateResponse succeededResponse() {
		return new CounselDraftCreateResponse(
			new CounselDraftCreateResponse.Data("019846dc-7c00-7000-8000-0000000006a1", CounselJobPhase.SUCCEEDED),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
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
