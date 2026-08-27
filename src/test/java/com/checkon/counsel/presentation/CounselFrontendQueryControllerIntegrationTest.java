package com.checkon.counsel.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@DisplayName("학부모 상담 프론트엔드 조회 API")
class CounselFrontendQueryControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("019f9100-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER = UUID.fromString("019f9100-0000-7000-8000-000000000002");
	private static final UUID PARENT = UUID.fromString("019f9100-0000-7000-8000-000000000010");
	private static final UUID OTHER_PARENT = UUID.fromString("019f9100-0000-7000-8000-000000000011");
	private static final UUID ENDED_PARENT = UUID.fromString("019f9100-0000-7000-8000-000000000012");
	private static final UUID ACTIVE_PARENT_WITHOUT_ACTIVE_STUDENT =
		UUID.fromString("019f9100-0000-7000-8000-000000000013");
	private static final UUID STUDENT = UUID.fromString("019f9100-0000-7000-8000-000000000020");
	private static final UUID NAMELESS_STUDENT = UUID.fromString("019f9100-0000-7000-8000-000000000021");
	private static final UUID OTHER_STUDENT = UUID.fromString("019f9100-0000-7000-8000-000000000022");
	private static final UUID ENDED_STUDENT = UUID.fromString("019f9100-0000-7000-8000-000000000023");
	private static final UUID CLASS = UUID.fromString("019f9100-0000-7000-8000-000000000030");
	private static final UUID OTHER_CLASS = UUID.fromString("019f9100-0000-7000-8000-000000000031");
	private static final Instant BASE = Instant.parse("2026-08-26T00:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM guardian_labels");
		jdbc.update("DELETE FROM counsel_draft_kafka_outbox_events");
		jdbc.update("DELETE FROM counsel_draft_jobs");
		jdbc.update("DELETE FROM counsel_inquiries");
		jdbc.update("DELETE FROM parent_student_relationships");
		jdbc.update("DELETE FROM parent_teacher_relationships");
		jdbc.update("DELETE FROM parent_profiles");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM student_personal_information");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");

		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		insertClass(CLASS, TEACHER, "고2 수학 A반");
		insertClass(OTHER_CLASS, OTHER_TEACHER, "다른 강사 반");
		insertStudent(STUDENT, TEACHER, CLASS, "김학생", true);
		insertStudent(NAMELESS_STUDENT, TEACHER, CLASS, null, true);
		insertStudent(OTHER_STUDENT, OTHER_TEACHER, OTHER_CLASS, "타강사 학생", true);
		insertStudent(ENDED_STUDENT, TEACHER, CLASS, "종료 학생", false);
		insertParent(PARENT, TEACHER, List.of(STUDENT, NAMELESS_STUDENT), true);
		insertParent(OTHER_PARENT, OTHER_TEACHER, List.of(OTHER_STUDENT), true);
		insertParent(ENDED_PARENT, TEACHER, List.of(ENDED_STUDENT), false);
		insertParent(ACTIVE_PARENT_WITHOUT_ACTIVE_STUDENT, TEACHER, List.of(ENDED_STUDENT), true);
		insertCommunications();
		insertCurrentLabel();
	}

	@Test
	@DisplayName("Given 활성 학부모와 학생 When 목록을 조회하면 Then 실제 ID와 학생 반 라벨 상담 요약을 반환한다")
	void returnsActiveGuardiansWithStudentsLabelsAndCommunicationSummary() throws Exception {
		mvc.perform(get("/api/v1/guardians").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.pageNumber").value(0))
			.andExpect(jsonPath("$.metadata.pageSize").value(20))
			.andExpect(jsonPath("$.metadata.itemCount").value(1))
			.andExpect(jsonPath("$.metadata.totalItemCount").value(1))
			.andExpect(jsonPath("$.items[0].parentId").value(PARENT.toString()))
			.andExpect(jsonPath("$.items[0].displayName").doesNotExist())
			.andExpect(jsonPath("$.items[0].students.length()").value(2))
			.andExpect(jsonPath("$.items[0].students[0].studentName").value("김학생"))
			.andExpect(jsonPath("$.items[0].students[0].classId").value(CLASS.toString()))
			.andExpect(jsonPath("$.items[0].labels[0].axis").value("comm"))
			.andExpect(jsonPath("$.items[0].communicationCount").value(6))
			.andExpect(jsonPath("$.items[0].latestInquiry.topic").value("grade"))
			.andExpect(jsonPath("$.items[0].latestInquiry.jobPhase").value("succeeded"));
	}

	@Test
	@DisplayName("Given 다른 강사의 학부모 When 상담 이력을 조회하면 Then 존재 여부를 404로 숨긴다")
	void hidesAnotherTeachersGuardianAsNotFound() throws Exception {
		mvc.perform(get("/api/v1/guardians/{parentId}/communications", OTHER_PARENT)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GUARDIAN_NOT_FOUND"));
	}

	@Test
	@DisplayName("Given 종료 관계와 활성 학생 없는 관계 When 목록을 조회하면 Then 공유 활성 학생이 있는 학부모만 노출한다")
	void excludesEndedAndInaccessibleRelationships() throws Exception {
		mvc.perform(get("/api/v1/guardians").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].parentId").value(PARENT.toString()));
	}

	@Test
	@DisplayName("Given 수신 문의와 실제 발송문 When 이력을 조회하면 Then 최신순과 nullable 학생 이름을 보존한다")
	void returnsInboundAndActuallySentMessagesNewestFirstWithNullableNames() throws Exception {
		mvc.perform(get("/api/v1/guardians/{parentId}/communications", PARENT)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.totalItemCount").value(6))
			.andExpect(jsonPath("$.items[0].direction").value("outbound"))
			.andExpect(jsonPath("$.items[0].actuallySent").value(true))
			.andExpect(jsonPath("$.items[0].jobId").value("job-3"))
			.andExpect(jsonPath("$.items[1].direction").value("inbound"))
			.andExpect(jsonPath("$.items[1].studentName").doesNotExist())
			.andExpect(jsonPath("$.items[5].recordId").value("inquiry:iq-1"));
	}

	@Test
	@DisplayName("Given 저장된 문의 컨텍스트와 draft job When 문의 목록을 조회하면 Then facts와 현재 job 상태를 연결한다")
	void returnsStoredDraftContextAndLatestJobState() throws Exception {
		mvc.perform(get("/api/v1/counsel/inquiries?size=1").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.totalItemCount").value(3))
			.andExpect(jsonPath("$.items[0].inquiryRef").value("iq-3"))
			.andExpect(jsonPath("$.items[0].parentId").value(PARENT.toString()))
			.andExpect(jsonPath("$.items[0].facts[0].recordId").value("learning-3"))
			.andExpect(jsonPath("$.items[0].facts[0].summary").value("최근 평가 3회 평균 83점"))
			.andExpect(jsonPath("$.items[0].draftJobCreated").value(true))
			.andExpect(jsonPath("$.items[0].jobId").value("job-3"))
			.andExpect(jsonPath("$.items[0].jobPhase").value("succeeded"));
	}

	@Test
	@DisplayName("Given 페이지 경계 When 빈 페이지와 잘못된 크기를 조회하면 Then 공통 빈 페이지와 400을 반환한다")
	void handlesEmptyPagesAndRejectsInvalidBounds() throws Exception {
		mvc.perform(get("/api/v1/guardians?page=2&size=1").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.itemCount").value(0))
			.andExpect(jsonPath("$.metadata.totalItemCount").value(1))
			.andExpect(jsonPath("$.metadata.isLast").value(true))
			.andExpect(jsonPath("$.items").isEmpty());

		mvc.perform(get("/api/v1/guardians?size=101").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	private void insertClass(UUID classId, UUID teacherId, String name) {
		jdbc.update("""
			INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, ?, '수학', 'ACTIVE', ?, ?)
			""", classId, teacherId, name, dbTime(BASE), dbTime(BASE));
	}

	private void insertStudent(UUID studentId, UUID teacherId, UUID classId, String realName, boolean active) {
		jdbc.update("INSERT INTO student_profiles (id, alias, created_at, updated_at) VALUES (?, ?, ?, ?)",
			studentId, "student-" + studentId, dbTime(BASE), dbTime(BASE));
		String status = active ? "ACTIVE" : "ENDED";
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, ended_at, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), teacherId, studentId, status, dbTime(BASE.minusSeconds(3600)),
			active ? null : dbTime(BASE.minusSeconds(60)), dbTime(BASE.minusSeconds(3600)));
		jdbc.update("""
			INSERT INTO class_enrollments
			(id, class_group_id, teacher_id, student_id, status, enrolled_at, ended_at, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), classId, teacherId, studentId, status, dbTime(BASE.minusSeconds(3600)),
			active ? null : dbTime(BASE.minusSeconds(60)), dbTime(BASE.minusSeconds(3600)));
		if (realName != null) {
			UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
			jdbc.update("""
				INSERT INTO student_personal_information
				(student_id, real_name, updated_by_account_id, updated_by_role, created_at, updated_at)
				VALUES (?, ?, ?, 'TEACHER', ?, ?)
				""", studentId, realName, accountId, dbTime(BASE), dbTime(BASE));
		}
	}

	private void insertParent(UUID parentId, UUID teacherId, List<UUID> students, boolean active) {
		UUID accountId = UUID.nameUUIDFromBytes(("parent:" + parentId).getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO accounts (id, email, role, status, created_at)
			VALUES (?, ?, 'PARENT', 'ACTIVE', ?)
			ON CONFLICT (id) DO NOTHING
			""", accountId, parentId + "@parent.test", dbTime(BASE));
		jdbc.update("""
			INSERT INTO parent_profiles (id, account_id, created_at, updated_at)
			VALUES (?, ?, ?, ?)
			""", parentId, accountId, dbTime(BASE), dbTime(BASE));
		String status = active ? "ACTIVE" : "ENDED";
		jdbc.update("""
			INSERT INTO parent_teacher_relationships
			(id, parent_id, teacher_id, status, started_at, ended_at, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), parentId, teacherId, status, dbTime(BASE.minusSeconds(3600)),
			active ? null : dbTime(BASE.minusSeconds(60)), dbTime(BASE.minusSeconds(3600)));
		for (UUID studentId : students) {
			jdbc.update("""
				INSERT INTO parent_student_relationships
				(id, parent_id, student_id, status, started_at, ended_at, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), parentId, studentId, status, dbTime(BASE.minusSeconds(3600)),
				active ? null : dbTime(BASE.minusSeconds(60)), dbTime(BASE.minusSeconds(3600)));
		}
	}

	private void insertCommunications() {
		insertCommunication("iq-1", "job-1", STUDENT, 1);
		insertCommunication("iq-2", "job-2", STUDENT, 2);
		insertCommunication("iq-3", "job-3", NAMELESS_STUDENT, 3);
	}

	private void insertCommunication(String inquiryRef, String jobId, UUID studentId, int order) {
		Instant received = BASE.plusSeconds(order * 120L);
		jdbc.update("""
			INSERT INTO counsel_inquiries (
			 id, teacher_id, inquiry_ref, student_id, class_id, topic, urgency, received_at,
			 raw_text, labels, dismissed_suggestions, period_label, facts, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, 'grade', 'normal', ?, ?, '["data"]', '[]',
			 '2026년 8월', CAST(? AS jsonb), ?, ?)
			""", UUID.randomUUID(), TEACHER, inquiryRef, studentId, CLASS, dbTime(received),
			"문의 본문 " + order, "[{\"recordId\":\"learning-" + order
				+ "\",\"summary\":\"최근 평가 3회 평균 8" + order + "점\"}]",
			dbTime(received), dbTime(received));
		jdbc.update("""
			INSERT INTO counsel_draft_jobs (
			 id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref, topic,
			 idempotency_key, job_id, job_phase, request_hash, requested_at, updated_at, sent_text, sent_at
			) VALUES (?, ?, 'tn_demo', ?, ?, 'pa_demo', 'cl_demo', 'grade', ?, ?, 'succeeded', ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), TEACHER, inquiryRef, "st-" + order, "idem-" + order, jobId,
			"sha256:" + "a".repeat(64), dbTime(received), dbTime(received.plusSeconds(30)),
			"실제 발송문 " + order, dbTime(received.plusSeconds(60)));
	}

	private void insertCurrentLabel() {
		jdbc.update("""
			INSERT INTO guardian_labels
			(id, teacher_id, parent_id, axis, value, source_suggestion_id, created_at, updated_at)
			VALUES (?, ?, ?, 'comm', 'data', 'demo:comm:data', ?, ?)
			""", UUID.randomUUID(), TEACHER, PARENT, dbTime(BASE), dbTime(BASE));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
		var principal = new AuthenticatedAccount(accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}

	private java.time.OffsetDateTime dbTime(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
