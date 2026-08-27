package com.checkon.member.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;

/**
 * PR6 §3 학생 질문 API 분기 검증. RLS 가 실제로 걸리는 역할에서 돈다.
 *
 * <p>🔴 강사 답변 API 는 이 PR 이 만들지 않는다 — {@code ANSWERED}·{@code FOLLOW_UP} 상태는
 * 리포지토리 레벨(관리자 커넥션)에서 직접 만든다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class QuestionIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String QUESTIONS = "/api/v1/member/students/me/questions";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID otherStudentAccountId;
	private UUID otherStudentProfileId;
	private UUID teacherId;
	private UUID assignmentId;
	private UUID otherAssignmentId;
	private UUID attemptId;
	private UUID itemId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now();

		assertThat(applicationRolePrivileges())
			.as("전제 — 애플리케이션 역할이 false/false 여야 RLS 단언이 유효하다")
			.isEqualTo("false/false");

		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = insertStudentFull(studentAccountId, "김학생", "STU-QQQ111", 2);
		otherStudentAccountId = insertAccount(admin, "other-student@example.com", "STUDENT", now);
		otherStudentProfileId = insertStudentFull(otherStudentAccountId, "이학생", "STU-QQQ222", 3);

		teacherId = insertTeacher(admin, "teacher@example.com", "박강사", now);

		activate(studentProfileId);
		activate(otherStudentProfileId);
		linkTeacher(teacherId, studentProfileId, "ACTIVE");
		// 🔴 다른 학생도 강사와 활성 관계여야 problem_generation_requests 트리거를 통과한다.
		linkTeacher(teacherId, otherStudentProfileId, "ACTIVE");

		assignmentId = insertAssignment(teacherId, studentProfileId);
		otherAssignmentId = insertAssignment(teacherId, otherStudentProfileId);
		attemptId = insertAttempt(assignmentId, studentProfileId);
		itemId = insertAttemptItem(attemptId);
	}

	// ── §3 학생 질문 작성 ────────────────────────────────────────

	@Test
	@DisplayName("🔴 teacherId 는 assignment 에서 온다 — body 로 넣어도 저장되지 않는다")
	void teacherComesFromAssignment() throws Exception {
		String body = "{\"assignmentId\":\"" + assignmentId + "\","
			+ "\"teacherId\":\"" + UUID.randomUUID() + "\","
			+ "\"title\":\"어렵다\",\"content\":\"이 문제 좀\"}";
		mockMvc.perform(post(QUESTIONS).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.questionId").exists());

		UUID savedTeacher = admin.queryForObject(
			"SELECT teacher_id FROM member_questions WHERE student_id = ?",
			UUID.class, studentProfileId);
		assertThat(savedTeacher).isEqualTo(teacherId);
	}

	@Test
	@DisplayName("🔴 남의 학생 assignment 는 404 (403 아님) · 저장 0")
	void otherStudentsAssignmentIs404() throws Exception {
		String body = "{\"assignmentId\":\"" + otherAssignmentId + "\","
			+ "\"title\":\"어렵다\",\"content\":\"안녕\"}";
		mockMvc.perform(post(QUESTIONS).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
		assertThat(count("member_questions", "student_id = ?", studentProfileId)).isZero();
	}

	@Test
	@DisplayName("🔴 남의 attempt 는 404")
	void foreignAttemptIs404() throws Exception {
		UUID foreignAttemptId = insertAttempt(otherAssignmentId, otherStudentProfileId);
		String body = "{\"assignmentId\":\"" + assignmentId + "\","
			+ "\"attemptId\":\"" + foreignAttemptId + "\","
			+ "\"title\":\"어렵다\",\"content\":\"안녕\"}";
		mockMvc.perform(post(QUESTIONS).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("🔴 itemId 단독은 400 (attemptId 없이는 불가)")
	void itemIdWithoutAttemptIs400() throws Exception {
		String body = "{\"assignmentId\":\"" + assignmentId + "\","
			+ "\"itemId\":\"" + itemId + "\","
			+ "\"title\":\"어렵다\",\"content\":\"안녕\"}";
		mockMvc.perform(post(QUESTIONS).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("itemId"));
	}

	@Test
	@DisplayName("🔴 관계 ENDED 는 422 RELATIONSHIP_REQUIRED")
	void endedRelationshipIs422() throws Exception {
		admin.update("UPDATE teacher_student_relationships SET status = 'ENDED', ended_at = ?"
			+ " WHERE teacher_id = ? AND student_id = ?", now, teacherId, studentProfileId);
		String body = "{\"assignmentId\":\"" + assignmentId + "\","
			+ "\"title\":\"어렵다\",\"content\":\"안녕\"}";
		mockMvc.perform(post(QUESTIONS).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("RELATIONSHIP_REQUIRED"));
	}

	// ── §3 후속 질문 ────────────────────────────────────────

	@Test
	@DisplayName("🔴 WAITING 상태에 messages POST 는 409 · 메시지 1행 유지")
	void waitingRejectsFollowUp() throws Exception {
		UUID questionId = createWaitingQuestion();
		mockMvc.perform(post(QUESTIONS + "/" + questionId + "/messages").with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"아직도\"}"))
			.andExpect(status().isConflict());
		assertThat(count("member_question_messages", "question_id = ?", questionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 ANSWERED → FOLLOW_UP: 201 · follow_up_count=1")
	void answeredBecomesFollowUp() throws Exception {
		UUID questionId = createWaitingQuestion();
		// 강사 답변을 관리자 커넥션으로 직접 만든다 — 강사 API 는 이 PR 이 만들지 않는다.
		admin.update("UPDATE member_questions SET status='ANSWERED', answered_at=?"
			+ " WHERE id = ?", now, questionId);
		admin.update("INSERT INTO member_question_messages"
			+ " (id, question_id, student_id, teacher_id, author_role, author_account_id,"
			+ "  content, published_at)"
			+ " VALUES (?, ?, ?, ?, 'TEACHER', ?, '답변', ?)",
			UUID.randomUUID(), questionId, studentProfileId, teacherId,
			teacherAccountFor(teacherId), now);

		mockMvc.perform(post(QUESTIONS + "/" + questionId + "/messages").with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"추가 질문\"}"))
			.andExpect(status().isCreated());

		Integer count = admin.queryForObject(
			"SELECT follow_up_count FROM member_questions WHERE id = ?",
			Integer.class, questionId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 FOLLOW_UP 상한 초과는 409 · 저장 0")
	void followUpCapEnforced() throws Exception {
		UUID questionId = createWaitingQuestion();
		// 상한 3 에 도달한 상태를 직접 만든다.
		admin.update("UPDATE member_questions SET status='FOLLOW_UP', answered_at=?,"
			+ " follow_up_count = 3 WHERE id = ?", now, questionId);

		int before = count("member_question_messages", "question_id = ?", questionId);
		mockMvc.perform(post(QUESTIONS + "/" + questionId + "/messages").with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"또\"}"))
			.andExpect(status().isConflict());
		int after = count("member_question_messages", "question_id = ?", questionId);
		assertThat(after - before).isZero();
	}

	// ── §3 목록·상세 ──────────────────────────────────────

	@Test
	@DisplayName("🔴 남의 학생은 A 의 질문·메시지를 못 본다 → RLS 0행, API 404")
	void otherStudentCannotRead() throws Exception {
		UUID questionId = createWaitingQuestion();
		mockMvc.perform(get(QUESTIONS + "/" + questionId).with(otherStudent()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("🔴 limit > 50 → 400 (조용한 절단 없음)")
	void listRejectsHighLimit() throws Exception {
		mockMvc.perform(get(QUESTIONS + "?limit=51").with(student()))
			.andExpect(status().isBadRequest());
	}

	// ── 헬퍼 ──────────────────────────────────────────

	private UUID insertStudentFull(UUID accountId, String name, String publicId, int grade) {
		UUID profileId = UUID.randomUUID();
		admin.update("INSERT INTO student_profiles (id, account_id, alias, grade,"
			+ " account_linked_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			profileId, accountId, name, grade, now, now, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name,"
			+ " created_at, updated_at) VALUES (?, ?, ?, ?)", accountId, name, now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", profileId, publicId, now);
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'PENDING_PARENT_LINK', NULL, ?, ?)",
			profileId, now, now);
		return profileId;
	}

	private UUID createWaitingQuestion() {
		UUID id = admin.queryForObject(
			"INSERT INTO member_questions"
				+ " (student_id, teacher_id, assignment_id, attempt_id, item_id,"
				+ "  title, content, status, follow_up_count, created_at)"
				+ " VALUES (?, ?, ?, NULL, NULL, ?, ?, 'WAITING', 0, ?) RETURNING id",
			UUID.class, studentProfileId, teacherId, assignmentId,
			"기존 질문", "안녕하세요", now);
		admin.update("INSERT INTO member_question_messages"
			+ " (id, question_id, student_id, teacher_id, author_role, author_account_id,"
			+ "  content, published_at)"
			+ " VALUES (?, ?, ?, ?, 'STUDENT', ?, ?, ?)",
			UUID.randomUUID(), id, studentProfileId, teacherId, studentAccountId,
			"안녕하세요", now);
		return id;
	}

	private UUID insertAssignment(UUID teacher, UUID student) {
		UUID requestId = insertProblemRequest(teacher, student);
		UUID setId = insertProblemSet(teacher, requestId);
		UUID assignmentId = UUID.randomUUID();
		admin.update("INSERT INTO problem_assignments"
			+ " (id, teacher_id, problem_request_id, problem_set_id, student_id, status,"
			+ "  published_at) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?)",
			assignmentId, teacher, requestId, setId, student, now);
		return assignmentId;
	}

	private UUID insertProblemRequest(UUID teacher, UUID student) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_requests"
			+ " (id, teacher_id, tenant_alias, target_kind, student_id, target_ref,"
			+ "  ai_idempotency_key, snapshot_hash, request_payload, status,"
			+ "  requested_at, updated_at)"
			+ " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)",
			id, teacher, "tn_" + hex(), student, "st_" + hex(),
			"pg_" + hex(), "sha256:" + hex() + hex(), now, now);
		return id;
	}

	private UUID insertProblemSet(UUID teacher, UUID requestId) {
		UUID setId = UUID.randomUUID();
		admin.update("INSERT INTO saved_problem_sets"
			+ " (id, teacher_id, problem_request_id, status, saved_at, updated_at)"
			+ " VALUES (?, ?, ?, 'SAVED', ?, ?)",
			setId, teacher, requestId, now, now);
		return setId;
	}

	private static String hex() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private UUID insertAttempt(UUID assignment, UUID student) {
		UUID id = UUID.randomUUID();
		UUID teacher = admin.queryForObject(
			"SELECT teacher_id FROM problem_assignments WHERE id = ?", UUID.class, assignment);
		admin.update("INSERT INTO member_attempts"
			+ " (id, student_id, assignment_id, teacher_id, status, version, snapshot_hash,"
			+ "  item_count, active_elapsed_sec, last_client_sequence, started_at,"
			+ "  last_progress_at, submitted_at, scored_at)"
			+ " VALUES (?, ?, ?, ?, 'IN_PROGRESS', 0, ?, 5, 0, NULL, ?, NULL, NULL, NULL)",
			id, student, assignment, teacher,
			"sha256:" + "0".repeat(64), now);
		return id;
	}

	private UUID insertAttemptItem(UUID attempt) {
		UUID itemId = UUID.randomUUID();
		admin.update("INSERT INTO member_attempt_items"
			+ " (attempt_id, item_id, ordinal, stem, passage, options, correct_no, explanation,"
			+ "  area_tag, type_tag, skill_node_id)"
			+ " VALUES (?, ?, 1, '문항', NULL, '[]'::jsonb, 1, NULL, 'calc', 'solve', NULL)",
			attempt, itemId);
		return itemId;
	}

	private void activate(UUID studentId) {
		admin.update("UPDATE member_student_activation SET status = 'ACTIVE', activated_at = ?"
			+ " WHERE student_id = ?", now, studentId);
	}

	private void linkTeacher(UUID teacher, UUID student, String status) {
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(), teacher, student, status, now, now);
	}

	private UUID teacherAccountFor(UUID teacher) {
		return admin.queryForObject("SELECT account_id FROM teacher_profiles WHERE id = ?",
			UUID.class, teacher);
	}

	private int count(String table, String whereClause, Object... args) {
		Integer count = admin.queryForObject(
			"SELECT count(*)::int FROM " + table + " WHERE " + whereClause, Integer.class, args);
		return count == null ? 0 : count;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor otherStudent() {
		return authentication(principalOf(otherStudentAccountId, AccountRole.STUDENT));
	}
}
