package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
		jdbcTemplate.update("DELETE FROM counsel_draft_kafka_outbox_events");
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
		@DisplayName("When 생성을 요청하면 Then 원본 문의 컨텍스트를 로컬에 보관하고 카프카 아웃박스에 발행한다")
		void persistsTheOriginalInquiryContextAndPublishesToTheOutbox() {
			var result = service.createDraft(TEACHER_ID, sampleCommand("iq_501", CounselTopic.GRADE));

			assertThat(result.status()).isEqualTo(CounselJobPhase.QUEUED);
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
			Integer outboxCount = jdbcTemplate.queryForObject(
				"""
				SELECT count(*) FROM counsel_draft_kafka_outbox_events o
				JOIN counsel_draft_jobs j ON j.id = o.counsel_draft_job_id
				WHERE j.teacher_id = ? AND j.job_id = ?
				""",
				Integer.class, TEACHER_ID, result.jobId()
			);
			assertThat(outboxCount).isEqualTo(1);
			verifyNoInteractions(client);
		}
	}

	@Nested
	@DisplayName("Given topic 정정으로 재생성할 때")
	class GivenRedraftingWithACorrectedTopic {

		@Test
		@DisplayName("When 이전에 만든 초안이 있으면 Then 같은 학생·반·근거로 새 Idempotency-Key를 써서 다시 요청한다")
		void redraftsWithTheSameContextAndAFreshIdempotencyKey() {
			var firstResult = service.createDraft(TEACHER_ID, sampleCommand("iq_502", CounselTopic.ETC));

			var redrafted = service.redraftWithCorrectedTopic(TEACHER_ID, "iq_502", CounselTopic.COUNSEL_REQUEST);

			assertThat(redrafted).isPresent();
			assertThat(redrafted.get().status()).isEqualTo(CounselJobPhase.QUEUED);
			assertThat(redrafted.get().jobId()).isNotEqualTo(firstResult.jobId());

			assertThat(topicOf(firstResult.jobId())).isEqualTo("etc");
			assertThat(topicOf(redrafted.get().jobId())).isEqualTo("counsel_request");
			assertThat(studentRefOf(redrafted.get().jobId())).isEqualTo(studentRefOf(firstResult.jobId()));
			assertThat(classRefOf(redrafted.get().jobId())).isEqualTo(classRefOf(firstResult.jobId()));
			assertThat(idempotencyKeyOf(redrafted.get().jobId())).isNotEqualTo(idempotencyKeyOf(firstResult.jobId()));

			Integer outboxCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_kafka_outbox_events WHERE teacher_id = ?",
				Integer.class, TEACHER_ID
			);
			assertThat(outboxCount).isEqualTo(2);
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When 이전에 만든 초안이 없으면 Then AI를 호출하지 않고 빈 값을 반환한다")
		void doesNothingWithoutAPriorDraft() {
			var redrafted = service.redraftWithCorrectedTopic(TEACHER_ID, "iq_never_drafted", CounselTopic.GRADE);

			assertThat(redrafted).isEmpty();
			verifyNoInteractions(client);
		}
	}

	private String topicOf(String jobId) {
		return jdbcTemplate.queryForObject(
			"SELECT topic FROM counsel_draft_jobs WHERE teacher_id = ? AND job_id = ?", String.class, TEACHER_ID, jobId);
	}

	private String studentRefOf(String jobId) {
		return jdbcTemplate.queryForObject(
			"SELECT student_ref FROM counsel_draft_jobs WHERE teacher_id = ? AND job_id = ?", String.class, TEACHER_ID, jobId);
	}

	private String classRefOf(String jobId) {
		return jdbcTemplate.queryForObject(
			"SELECT class_ref FROM counsel_draft_jobs WHERE teacher_id = ? AND job_id = ?", String.class, TEACHER_ID, jobId);
	}

	private String idempotencyKeyOf(String jobId) {
		return jdbcTemplate.queryForObject(
			"SELECT idempotency_key FROM counsel_draft_jobs WHERE teacher_id = ? AND job_id = ?", String.class, TEACHER_ID, jobId);
	}

	private CreateCounselInquiryCommand sampleCommand(String inquiryRef, CounselTopic topic) {
		return new CreateCounselInquiryCommand(
			STUDENT_ID, CLASS_ID, inquiryRef, inquiryRef, topic, CounselUrgency.IMMEDIATE,
			OffsetDateTime.parse("2026-08-20T14:20:00+09:00"), "요즘 아이가 힘들어하는 것 같아요",
			List.of("narrative", "anxious"), List.of(), "2026년 8월",
			List.of(new CreateCounselDraftCommand.Fact("le_2041", "6월 지문 42개·312문항"))
		);
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
