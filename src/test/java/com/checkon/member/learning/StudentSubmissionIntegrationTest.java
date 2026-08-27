package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
	private static final String GET_ATTEMPT =
		"/api/v1/member/students/me/attempts/{id}";
	private static final String RESULT =
		"/api/v1/member/students/me/attempts/{id}/result";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	private JdbcTemplate admin;
	private UUID studentAccountId;
	private UUID studentId;
	private UUID assignmentId;
	private UUID itemA;
	private UUID itemB;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		// 🔴 결과 원장 둘은 이제 clearFixtures 가 자식 → 부모 순서로 함께 지운다.
		//    예전에는 여기서 `DELETE FROM problem_assignment_responses` 를 WHERE 없이
		//    돌렸는데, 컨테이너를 공유하므로 그것이 옆 테스트의 픽스처까지 지웠다.
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now();
		UUID teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentId = MemberPostgresSupport.insertStudentProfile(
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

	/**
	 * 🔴 이 스위트가 쓴 결과 원장을 <b>이 학생 범위로만</b> 되돌린다.
	 * 정리 SQL 은 {@code MemberPostgresSupport} 한 곳에 있다.
	 */
	@AfterEach
	void clearWrittenLedgers() {
		MemberPostgresSupport.deleteSubmissionLedgersForStudent(admin, studentId);
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

	@Test
	@DisplayName("S3 이월 — SCORED attempt GET과 result는 200 + 정답·해설이다")
	void scoredAttemptAndResultReturn200() throws Exception {
		UUID attemptId = createAttempt();
		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(0, Map.of(itemA, 1, itemB, 2), Map.of())).andExpect(status().isOk());

		mockMvc.perform(get(GET_ATTEMPT, attemptId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SCORED"))
			.andExpect(jsonPath("$.data.items[0].correctNo").exists())
			.andExpect(jsonPath("$.data.items[0].explanation").exists());
		mockMvc.perform(get(RESULT, attemptId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SCORED"))
			.andExpect(jsonPath("$.data.items.length()").value(2));
	}

	@Test
	@DisplayName("S3 이월 — SUBMITTED attempt GET은 200이고 정답·해설이 없다")
	void submittedAttemptReturns200WithoutAnswers() throws Exception {
		UUID attemptId = createAttempt();
		admin.update("UPDATE member_attempts SET status='SUBMITTED', version=1, submitted_at=?"
			+ " WHERE id=?", OffsetDateTime.now(), attemptId);

		String body = mockMvc.perform(get(GET_ATTEMPT, attemptId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("correctNo").doesNotContain("explanation");
	}

	@Test
	@DisplayName("result — IN_PROGRESS attempt는 404다")
	void resultBeforeSubmissionIs404() throws Exception {
		UUID attemptId = createAttempt();
		mockMvc.perform(get(RESULT, attemptId).with(student()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("제출 — 다른 key 동시 2요청은 하나만 채점하고 원장을 한 벌만 쓴다")
	void concurrentSubmitWritesOnce() throws Exception {
		UUID attemptId = createAttempt();
		String body = submissionBody(0, Map.of(itemA, 1, itemB, 2), Map.of());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var tasks = List.of(
				executor.submit(() -> concurrentSubmit(attemptId, body, ready, start)),
				executor.submit(() -> concurrentSubmit(attemptId, body, ready, start)));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Integer> statuses = List.of(
				tasks.get(0).get(10, TimeUnit.SECONDS),
				tasks.get(1).get(10, TimeUnit.SECONDS));
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM learning_records WHERE external_record_ref LIKE ?",
			Integer.class, attemptId + "%")).isEqualTo(3);
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM problem_assignment_responses WHERE assignment_id = ?",
			Integer.class, assignmentId)).isEqualTo(2);
	}

	@Test
	@DisplayName("MB-05 — 전 문항 미응답이어도 제출을 허용하고 SUBMIT 사건은 남긴다")
	void unansweredSubmissionIsAllowed() throws Exception {
		UUID attemptId = createAttempt();
		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(0, Map.of(), Map.of()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SCORED"))
			.andExpect(jsonPath("$.data.correctCount").value(0));
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM problem_assignment_responses WHERE assignment_id = ?",
			Integer.class, assignmentId)).isZero();
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM learning_records WHERE external_record_ref = ?",
			Integer.class, attemptId.toString())).isEqualTo(1);
	}

	@Test
	@DisplayName("제출 — Idempotency-Key가 없으면 400 INVALID_REQUEST다")
	void missingIdempotencyKeyIsRejected() throws Exception {
		UUID attemptId = createAttempt();
		mockMvc.perform(post(SUBMIT, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON)
				.content(submissionBody(0, Map.of(itemA, 1), Map.of())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(admin.queryForObject(
			"SELECT status FROM member_attempts WHERE id = ?", String.class, attemptId))
			.isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("제출 — baseVersion 불일치는 409 REVISION_CONFLICT고 원장을 쓰지 않는다")
	void staleBaseVersionIsRejected() throws Exception {
		UUID attemptId = createAttempt();
		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(1, Map.of(itemA, 1), Map.of()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("REVISION_CONFLICT"));
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM problem_assignment_responses WHERE assignment_id = ?",
			Integer.class, assignmentId)).isZero();
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM learning_records WHERE external_record_ref LIKE ?",
			Integer.class, attemptId + "%")).isZero();
	}

	@Test
	@DisplayName("제출 — 대기 학생은 403이고 attempt와 원장은 변하지 않는다")
	void pendingStudentIsForbidden() throws Exception {
		UUID attemptId = createAttempt();
		admin.update("UPDATE member_student_activation SET status='PENDING_PARENT_LINK',"
			+ " activated_at=NULL, updated_at=? WHERE student_id=?",
			OffsetDateTime.now(), studentId);
		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(0, Map.of(itemA, 1), Map.of()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));
		assertThat(admin.queryForObject(
			"SELECT status FROM member_attempts WHERE id = ?", String.class, attemptId))
			.isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("제출 — 오답 선택지의 misconceptionTag가 사라지면 422와 전체 롤백이다")
	void corruptedMisconceptionTagRollsBack() throws Exception {
		UUID attemptId = createAttempt();
		admin.update("UPDATE member_attempt_items SET options ="
			+ " jsonb_set(options, '{1,misconceptionTag}', 'null'::jsonb)"
			+ " WHERE attempt_id=? AND item_id=?", attemptId, itemA);

		submit(attemptId, UUID.randomUUID().toString(),
			submissionBody(0, Map.of(itemA, 2), Map.of()))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("WORKSHEET_NOT_GRADABLE"))
			.andExpect(jsonPath("$.error.details.missing[0]").value("misconceptionTag"));
		assertThat(admin.queryForObject(
			"SELECT status FROM member_attempts WHERE id = ?", String.class, attemptId))
			.isEqualTo("IN_PROGRESS");
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM member_attempt_events WHERE attempt_id = ?"
				+ " AND event_type IN ('SUBMITTED','SCORED')", Integer.class, attemptId))
			.isZero();
	}

	private org.springframework.test.web.servlet.ResultActions submit(
		UUID attemptId, String key, String body
	) throws Exception {
		return mockMvc.perform(post(SUBMIT, attemptId).with(student())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private int concurrentSubmit(
		UUID attemptId, String body, CountDownLatch ready, CountDownLatch start
	) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("concurrent submit start latch timed out");
		}
		return mockMvc.perform(post(SUBMIT, attemptId).with(student())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andReturn().getResponse().getStatus();
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
