package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@DisplayName("상담 초안 요청 오케스트레이션")
class CounselDraftRequestServiceTest {

	private static final UUID TEACHER_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000a01");
	private static final UUID STUDENT_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000a11");
	private static final UUID CLASS_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000a21");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-20T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@MockitoBean
	private CounselClient client;

	@Autowired
	private CounselDraftRequestService service;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM counsel_draft_jobs");
		jdbcTemplate.update("DELETE FROM counsel_inquiries");
		jdbcTemplate.update("DELETE FROM ai_guardian_aliases");
		jdbcTemplate.update("DELETE FROM ai_class_aliases");
		jdbcTemplate.update("DELETE FROM ai_tenant_aliases");
		jdbcTemplate.update("DELETE FROM ai_student_aliases");
		jdbcTemplate.update("DELETE FROM student_personal_information");
		jdbcTemplate.update("DELETE FROM class_enrollments");
		jdbcTemplate.update("DELETE FROM teacher_student_relationships");
		jdbcTemplate.update("DELETE FROM class_groups");
		jdbcTemplate.update("DELETE FROM student_profiles");

		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
		insertStudentAndRelationship(STUDENT_ID, TEACHER_ID);
		insertClassGroup(CLASS_ID, TEACHER_ID);
	}

	@Nested
	@DisplayName("Given 초안을 생성할 때")
	class GivenCreatingADraft {

		@Test
		@DisplayName("When 생성에 성공하면 Then 원본 문의 컨텍스트를 로컬에 보관한다")
		void persistsTheOriginalInquiryContext() {
			when(client.createDraft(any(), any())).thenReturn(succeededResponse("cj_2001"));

			service.createDraft(TEACHER_ID, sampleCommand("iq_501", CounselTopic.GRADE));

			String rawText = jdbcTemplate.queryForObject(
				"SELECT raw_text FROM counsel_inquiries WHERE teacher_id = ? AND inquiry_ref = ?",
				String.class, TEACHER_ID, "iq_501"
			);
			assertThat(rawText).isEqualTo("요즘 아이가 힘들어하는 것 같아요");
			String topic = jdbcTemplate.queryForObject(
				"SELECT topic FROM counsel_inquiries WHERE teacher_id = ? AND inquiry_ref = ?",
				String.class, TEACHER_ID, "iq_501"
			);
			assertThat(topic).isEqualTo("grade");
		}
	}

	@Nested
	@DisplayName("Given topic 정정으로 재생성할 때")
	class GivenRedraftingWithACorrectedTopic {

		@Test
		@DisplayName("When 이전에 만든 초안이 있으면 Then 같은 학생·반·근거로 새 Idempotency-Key를 써서 다시 요청한다")
		void redraftsWithTheSameContextAndAFreshIdempotencyKey() {
			when(client.createDraft(any(), any())).thenReturn(succeededResponse("cj_2002"));
			service.createDraft(TEACHER_ID, sampleCommand("iq_502", CounselTopic.ETC));

			when(client.createDraft(any(), any())).thenReturn(succeededResponse("cj_2003"));
			var redrafted = service.redraftWithCorrectedTopic(TEACHER_ID, "iq_502", CounselTopic.COUNSEL_REQUEST);

			ArgumentCaptor<CounselDraftCreateRequest> requests = ArgumentCaptor.forClass(CounselDraftCreateRequest.class);
			ArgumentCaptor<CounselClient.RequestHeaders> headers = ArgumentCaptor.forClass(CounselClient.RequestHeaders.class);
			verify(client, times(2)).createDraft(requests.capture(), headers.capture());
			CounselDraftCreateRequest firstRequest = requests.getAllValues().get(0);
			CounselDraftCreateRequest secondRequest = requests.getAllValues().get(1);
			String firstIdempotencyKey = headers.getAllValues().get(0).idempotencyKey();
			String secondIdempotencyKey = headers.getAllValues().get(1).idempotencyKey();

			assertThat(redrafted).isPresent();
			assertThat(redrafted.get().jobId()).isEqualTo("cj_2003");
			assertThat(secondRequest.inquiry().topic()).isEqualTo(CounselTopic.COUNSEL_REQUEST);
			assertThat(secondRequest.studentRef()).isEqualTo(firstRequest.studentRef());
			assertThat(secondRequest.classRef()).isEqualTo(firstRequest.classRef());
			assertThat(secondRequest.context().facts()).isEqualTo(firstRequest.context().facts());
			assertThat(secondIdempotencyKey).isNotEqualTo(firstIdempotencyKey);
		}

		@Test
		@DisplayName("When 이전에 만든 초안이 없으면 Then AI를 호출하지 않고 빈 값을 반환한다")
		void doesNothingWithoutAPriorDraft() {
			var redrafted = service.redraftWithCorrectedTopic(TEACHER_ID, "iq_never_drafted", CounselTopic.GRADE);

			assertThat(redrafted).isEmpty();
			verifyNoInteractions(client);
		}
	}

	private CreateCounselInquiryCommand sampleCommand(String inquiryRef, CounselTopic topic) {
		return new CreateCounselInquiryCommand(
			STUDENT_ID, CLASS_ID, inquiryRef, inquiryRef, topic, CounselUrgency.IMMEDIATE,
			OffsetDateTime.parse("2026-08-20T14:20:00+09:00"), "요즘 아이가 힘들어하는 것 같아요",
			List.of("narrative", "anxious"), List.of(), "2026년 8월",
			List.of(new CreateCounselDraftCommand.Fact("le_2041", "6월 지문 42개·312문항"))
		);
	}

	private static CounselDraftCreateResponse succeededResponse(String jobId) {
		return new CounselDraftCreateResponse(
			new CounselDraftCreateResponse.Data(jobId, CounselJobPhase.SUCCEEDED),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
	}

	private static CounselMeta.Versions versions() {
		return new CounselMeta.Versions("0.1.0", "counsel-pack-0.1", null, "0.3", "0.1", "0.1", null, null, null, null);
	}

	private void insertStudentAndRelationship(UUID studentId, UUID teacherId) {
		jdbcTemplate.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, "student-" + studentId, time(), time());
		jdbcTemplate.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", teacherId, studentId, time(), time());
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
		jdbcTemplate.update("""
			INSERT INTO student_personal_information
			    (student_id, real_name, updated_by_account_id, updated_by_role, created_at, updated_at)
			VALUES (?, '박서연', ?, 'TEACHER', ?, ?)
			""", studentId, accountId, time(), time());
	}

	private void insertClassGroup(UUID classId, UUID teacherId) {
		jdbcTemplate.update("""
			INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, '문법반', '국어', 'ACTIVE', ?, ?)
			""", classId, teacherId, time(), time());
	}

	private static OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}
}
