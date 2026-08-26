package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;
import tools.jackson.databind.ObjectMapper;

/** 제출·채점의 한 트랜잭션과 멱등 재생을 제한 DB 역할 위에서 검증한다. */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class StudentSubmissionIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String START =
		"/api/v1/member/students/me/worksheets/{id}/attempts";
	private static final String SUBMIT =
		"/api/v1/member/students/me/attempts/{id}/submission";
	private static final String PROGRESS =
		"/api/v1/member/students/me/attempts/{id}/progress";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	private JdbcTemplate admin;
	private UUID studentAccountId;
	private UUID assignmentId;
	private UUID itemA;
	private UUID itemB;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		// 이 스위트가 처음으로 승우님 결과 원장에 행을 쓴다. 공용 member fixture 정리는
		// 그 FK를 아직 모르므로, 참조 대상 assignment를 지우기 전에 결과 행부터 정리한다.
		admin.update("DELETE FROM problem_assignment_responses");
		admin.update("DELETE FROM learning_records WHERE source_type LIKE 'member_attempt%'");
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now();
		UUID teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		UUID studentId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccountId, "박학생", null, now);
		LearningFixtures.activateStudent(admin, studentId, now);
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);
		UUID requestId = LearningFixtures.insertProblemRequest(admin, teacherId, studentId, now);
		UUID setId = LearningFixtures.insertProblemSet(admin, teacherId, requestId, now);
		itemA = LearningFixtures.insertProblemItem(admin, teacherId, requestId, 1, now);
		itemB = LearningFixtures.insertProblemItem(admin, teacherId, requestId, 2, now);
		LearningFixtures.insertGradableItem(admin, teacherId, requestId, setId, itemA, 1, 1);
		LearningFixtures.insertGradableItem(admin, teacherId, requestId, setId, itemB, 2, 2);
		assignmentId = LearningFixtures.insertAssignment(
			admin, teacherId, requestId, setId, studentId, now);
	}

	@Test
	@DisplayName("제출 — 같은 트랜잭션에서 채점·응답·학습기록·세션·이벤트를 남긴다")
	void scoresAndWritesBothLedgers() throws Exception {
		UUID attemptId = createAttempt();
		String body = submissionBody(0, Map.of(itemA, 1, itemB, 2), Map.of(itemA, 17));

		mockMvc.perform(post(SUBMIT, attemptId).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SCORED"))
			.andExpect(jsonPath("$.data.correctCount").value(2))
			.andExpect(jsonPath("$.data.totalActiveElapsedSeconds").value(17))
			.andExpect(jsonPath("$.data.learningRecordId").isNotEmpty())
			.andExpect(jsonPath("$.data.items[0].correctNo").exists())
			.andExpect(jsonPath("$.data.items[0].explanation").exists());

		assertThat(count("problem_assignment_responses", attemptId)).isEqualTo(2);
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM learning_records WHERE external_record_ref LIKE ?",
			Integer.class, attemptId + "%")).isEqualTo(3);
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM member_attempt_events WHERE attempt_id = ?"
				+ " AND event_type IN ('SUBMITTED','SCORED')", Integer.class, attemptId))
			.isEqualTo(2);
		assertThat(admin.queryForObject(
			"SELECT submit_record_id IS NOT NULL FROM member_learning_sessions WHERE attempt_id = ?",
			Boolean.class, attemptId)).isTrue();
	}

	@Test
	@DisplayName("제출 — 같은 key·body 재전송은 동일 body를 재생하고 원장을 중복하지 않는다")
	void replaysSameKeyAndBody() throws Exception {
		UUID attemptId = createAttempt();
		String key = UUID.randomUUID().toString();
		String body = submissionBody(0, Map.of(itemA, 1, itemB, 2), Map.of());

		MvcResult first = submit(attemptId, key, body).andExpect(status().isOk()).andReturn();
		MvcResult second = submit(attemptId, key, body).andExpect(status().isOk()).andReturn();

		assertThat(second.getResponse().getContentAsString())
			.isEqualTo(first.getResponse().getContentAsString());
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM learning_records WHERE external_record_ref LIKE ?",
			Integer.class, attemptId + "%")).isEqualTo(3);
	}

	@Test
	@DisplayName("제출 — 같은 key에 다른 body면 409 IDEMPOTENCY_CONFLICT")
	void rejectsSameKeyDifferentBody() throws Exception {
		UUID attemptId = createAttempt();
		String key = UUID.randomUUID().toString();
		submit(attemptId, key, submissionBody(0, Map.of(itemA, 1), Map.of()))
			.andExpect(status().isOk());
		submit(attemptId, key, submissionBody(1, Map.of(itemA, 2), Map.of()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test
	@DisplayName("S3 이월 — SCORED assignment를 다시 시작하면 409다")
	void scoredAssignmentCannotStartAgain() throws Exception {
		UUID attemptId = createAttempt();
		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(0, Map.of(itemA, 1), Map.of())).andExpect(status().isOk());

		mockMvc.perform(post(START, assignmentId).with(student()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ATTEMPT_ALREADY_SUBMITTED"));
	}

	@Test
	@DisplayName("S3 이월 — 제출된 attempt의 progress는 409다")
	void submittedAttemptRejectsProgress() throws Exception {
		UUID attemptId = createAttempt();
		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(0, Map.of(itemA, 1), Map.of())).andExpect(status().isOk());
		String progress = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 2, "clientSequence", 1));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(progress))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ATTEMPT_ALREADY_SUBMITTED"));
	}

	private org.springframework.test.web.servlet.ResultActions submit(
		UUID attemptId, String key, String body
	) throws Exception {
		return mockMvc.perform(post(SUBMIT, attemptId).with(student())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private UUID createAttempt() throws Exception {
		String body = mockMvc.perform(post(START, assignmentId).with(student()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(body).get("data").get("attemptId").asText());
	}

	private String submissionBody(
		int baseVersion, Map<UUID, Integer> answers, Map<UUID, Integer> deltas
	) {
		return objectMapper.writeValueAsString(Map.of(
			"baseVersion", baseVersion,
			"answers", answers,
			"activeElapsedSecondsDelta", deltas));
	}

	private Integer count(String table, UUID attemptId) {
		if (!"problem_assignment_responses".equals(table)) {
			throw new IllegalArgumentException("unsupported table");
		}
		return admin.queryForObject(
			"SELECT count(*) FROM problem_assignment_responses WHERE assignment_id = ?",
			Integer.class, assignmentId);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}
}
