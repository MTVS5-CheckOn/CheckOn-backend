package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;
import tools.jackson.databind.ObjectMapper;

/**
 * S3 · attempt 시작·재개·조회·progress 자동저장. 🔴 <b>새 테스트 클래스인 이유</b> —
 * {@link StudentWorksheetIntegrationTest} 는 이미 370줄이고 클래스당 400줄 상한(G7-a)에
 * 위험할 만큼 가깝다. 이 스위트는 15개+ 시나리오라 합치면 상한을 넘는다.
 * G17 approved combo 는 동일하다({@code @SpringBootTest} properties).
 *
 * <p>🔴 <b>MockMvc + 보안 체인</b> 위에서 응답 JSON 을 단정한다 — 컨트롤러의 201/200 갈림과
 * IN_PROGRESS 응답 계약(정답·해설 부재)이 wire 형태로 지켜지는지 확인한다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class StudentAttemptIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String WORKSHEET_ATTEMPTS =
		"/api/v1/member/students/me/worksheets/{id}/attempts";
	private static final String GET_ATTEMPT =
		"/api/v1/member/students/me/attempts/{id}";
	private static final String PROGRESS =
		"/api/v1/member/students/me/attempts/{id}/progress";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;
	@Autowired ObjectMapper objectMapper;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID teacherId;
	private UUID studentAccountId;
	private UUID studentId;
	private UUID otherAccountId;
	private UUID otherStudentId;
	private UUID assignmentId;
	private UUID itemA;
	private UUID itemB;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		clearFixtures(admin);
		now = OffsetDateTime.now();

		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccountId, "박학생", null, now);
		LearningFixtures.activateStudent(admin, studentId, now);

		otherAccountId = insertAccount(admin, "other@example.com", "STUDENT", now);
		otherStudentId = MemberPostgresSupport.insertStudentProfile(
			admin, otherAccountId, "이학생", null, now);
		LearningFixtures.activateStudent(admin, otherStudentId, now);

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

		rateLimiter.resetForTesting();
		rateLimiter.overridePermitsForTesting(1000);
	}

	@AfterEach
	void tearDown() {
		rateLimiter.resetForTesting();
	}

	// ────────────────────────── start / resume ──────────────────────────

	@Test
	@DisplayName("start — 새 attempt 는 201 이다 (member_attempts 1행)")
	void startNewReturns201() throws Exception {
		MvcResult result = mockMvc.perform(post(WORKSHEET_ATTEMPTS, assignmentId).with(student()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.snapshotHash").exists())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		// 🔴 절대 규칙 4 — wire 에 정답·해설·정오 키가 새어나가면 안 된다.
		assertThat(body).doesNotContain("correctNo").doesNotContain("explanation");
		assertThat(body).as("\"correct\":true/false 키 자체가 없어야 한다").doesNotContain("\"correct\"");
		Integer rows = admin.queryForObject(
			"SELECT count(*) FROM member_attempts WHERE assignment_id = ?",
			Integer.class, assignmentId);
		assertThat(rows).isEqualTo(1);
	}

	@Test
	@DisplayName("start — 두 번째 요청은 200 이고 같은 attemptId 를 돌려준다 (재개)")
	void secondStartResumes() throws Exception {
		String firstBody = mockMvc.perform(post(WORKSHEET_ATTEMPTS, assignmentId).with(student()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String firstId = jsonAttemptId(firstBody);

		String secondBody = mockMvc.perform(post(WORKSHEET_ATTEMPTS, assignmentId).with(student()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String secondId = jsonAttemptId(secondBody);

		assertThat(secondId).isEqualTo(firstId);
		Integer rows = admin.queryForObject(
			"SELECT count(*) FROM member_attempts WHERE assignment_id = ?",
			Integer.class, assignmentId);
		assertThat(rows).isEqualTo(1);
	}

	@Test
	@DisplayName("start — 채점 불가 학습지는 422 이고 attempt 는 생성되지 않는다")
	void startReturns422WhenUngradable() throws Exception {
		UUID requestId2 = LearningFixtures.insertProblemRequest(admin, teacherId, studentId, now);
		UUID setId2 = LearningFixtures.insertProblemSet(admin, teacherId, requestId2, now);
		UUID badItem = LearningFixtures.insertProblemItem(admin, teacherId, requestId2, 1, now);
		LearningFixtures.insertUngradableItem(admin, teacherId, requestId2, setId2, badItem, 1);
		UUID uglyAssignment = LearningFixtures.insertAssignment(
			admin, teacherId, requestId2, setId2, studentId, now);

		mockMvc.perform(post(WORKSHEET_ATTEMPTS, uglyAssignment).with(student()))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("WORKSHEET_NOT_GRADABLE"))
			.andExpect(jsonPath("$.error.details.itemIds[0]").value(badItem.toString()));

		Integer attempts = admin.queryForObject(
			"SELECT count(*) FROM member_attempts WHERE assignment_id = ?",
			Integer.class, uglyAssignment);
		assertThat(attempts).as("422 는 attempt 를 만들지 않는다").isZero();
	}

	@Test
	@DisplayName("start — 남의 학습지는 404 다")
	void startForForeignAssignmentIs404() throws Exception {
		mockMvc.perform(post(WORKSHEET_ATTEMPTS, assignmentId).with(otherStudent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	// ─────────────────────────── get ───────────────────────────

	@Test
	@DisplayName("get — IN_PROGRESS 이면 200 이고 정답·해설 키가 없다")
	void getInProgress() throws Exception {
		UUID attemptId = createAttempt();
		MvcResult result = mockMvc.perform(get(GET_ATTEMPT, attemptId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].correctNo").doesNotExist())
			.andExpect(jsonPath("$.data.items[0].explanation").doesNotExist())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("correctNo").doesNotContain("explanation");
	}

	@Test
	@DisplayName("get — 남의 attempt 는 RLS 로 안 보여 404 다")
	void getForeignAttemptIs404() throws Exception {
		UUID attemptId = createAttempt();
		mockMvc.perform(get(GET_ATTEMPT, attemptId).with(otherStudent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("get — 존재하지 않는 UUID 는 404 다")
	void getMissingIs404() throws Exception {
		mockMvc.perform(get(GET_ATTEMPT, UUID.randomUUID()).with(student()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("🔴 조회 경로가 saved_problem_set_items 를 다시 읽지 않는다 (스냅샷 동결)")
	void getReadsOnlyFrozenSnapshot() throws Exception {
		UUID attemptId = createAttempt();
		admin.update("UPDATE saved_problem_set_items SET item_snapshot ="
			+ " jsonb_set(item_snapshot, '{stem}', '\"위조본문\"') WHERE item_id = ?", itemA);
		String body = mockMvc.perform(get(GET_ATTEMPT, attemptId).with(student()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(body).as("복사 이후 조회가 원본을 다시 읽으면 「위조본문」이 새어나온다")
			.contains("본문 1").doesNotContain("위조본문");
	}

	// ─────────────────────────── progress ───────────────────────────

	@Test
	@DisplayName("progress — 정상 저장은 200 + duplicated=false + version+1")
	void progressNormal() throws Exception {
		UUID attemptId = createAttempt();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1,
			"answers", Map.of(itemA.toString(), 1),
			"activeElapsedSecondsDelta", Map.of(itemA.toString(), 30)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicated").value(false))
			.andExpect(jsonPath("$.data.version").value(1))
			.andExpect(jsonPath("$.data.totalActiveElapsedSeconds").value(30));
		Integer selected = admin.queryForObject(
			"SELECT selected_no FROM member_attempt_answers WHERE attempt_id = ? AND item_id = ?",
			Integer.class, attemptId, itemA);
		Integer elapsed = admin.queryForObject(
			"SELECT active_elapsed_sec FROM member_attempt_answers"
				+ " WHERE attempt_id = ? AND item_id = ?",
			Integer.class, attemptId, itemA);
		assertThat(selected).isEqualTo(1);
		assertThat(elapsed).isEqualTo(30);
	}

	@Test
	@DisplayName("progress — 같은 clientSequence 재전송은 duplicated=true (기록 불변)")
	void progressDedupe() throws Exception {
		UUID attemptId = createAttempt();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 5,
			"answers", Map.of(itemA.toString(), 2),
			"activeElapsedSecondsDelta", Map.of(itemA.toString(), 42)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
			.contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicated").value(true))
			.andExpect(jsonPath("$.data.version").value(1))
			.andExpect(jsonPath("$.data.totalActiveElapsedSeconds").value(42));
		Integer elapsed = admin.queryForObject(
			"SELECT active_elapsed_sec FROM member_attempts WHERE id = ?",
			Integer.class, attemptId);
		assertThat(elapsed).as("두 번째 요청이 시간을 두 번 더하면 안 된다").isEqualTo(42);
	}

	@Test
	@DisplayName("progress — baseVersion 이 하나 낮으면 409 REVISION_CONFLICT (저장 0건)")
	void progressStaleBaseVersion() throws Exception {
		UUID attemptId = createAttempt();
		String first = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1,
			"answers", Map.of(itemA.toString(), 1),
			"activeElapsedSecondsDelta", Map.of(itemA.toString(), 10)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
			.contentType(MediaType.APPLICATION_JSON).content(first)).andExpect(status().isOk());
		String stale = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 2,
			"answers", Map.of(itemA.toString(), 2),
			"activeElapsedSecondsDelta", Map.of(itemA.toString(), 99)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(stale))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("REVISION_CONFLICT"));
		Integer elapsed = admin.queryForObject(
			"SELECT active_elapsed_sec FROM member_attempts WHERE id = ?",
			Integer.class, attemptId);
		assertThat(elapsed).as("409 는 저장 0건이어야 한다").isEqualTo(10);
	}

	@Test
	@DisplayName("progress — delta 음수는 400 (조용히 0 으로 보정하지 않는다)")
	void progressRejectsNegativeDelta() throws Exception {
		UUID attemptId = createAttempt();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1,
			"activeElapsedSecondsDelta", Map.of(itemA.toString(), -1)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("progress — delta 가 상한 초과면 400 (조용히 clamp 하지 않는다)")
	void progressRejectsOverLimitDelta() throws Exception {
		UUID attemptId = createAttempt();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1,
			"activeElapsedSecondsDelta", Map.of(itemA.toString(), 601)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		Integer elapsed = admin.queryForObject(
			"SELECT active_elapsed_sec FROM member_attempts WHERE id = ?",
			Integer.class, attemptId);
		assertThat(elapsed).as("clamp 하지 않는다 — 저장 0").isZero();
	}

	@Test
	@DisplayName("progress — attempt 소속이 아닌 itemId 는 400 (details.itemIds)")
	void progressRejectsUnknownItemId() throws Exception {
		UUID attemptId = createAttempt();
		UUID unknown = UUID.randomUUID();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1,
			"answers", Map.of(unknown.toString(), 1)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.details.itemIds[0]").value(unknown.toString()));
	}

	@Test
	@DisplayName("progress — 보기 번호가 범위 밖이면 400")
	void progressRejectsSelectedOutOfRange() throws Exception {
		UUID attemptId = createAttempt();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1,
			"answers", Map.of(itemA.toString(), 99)));
		mockMvc.perform(patch(PROGRESS, attemptId).with(student())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("progress — 남의 attempt 는 404")
	void progressForeignAttemptIs404() throws Exception {
		UUID attemptId = createAttempt();
		String payload = objectMapper.writeValueAsString(Map.of(
			"baseVersion", 0, "clientSequence", 1));
		mockMvc.perform(patch(PROGRESS, attemptId).with(otherStudent())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	// ─────────────────────────── 헬퍼 ───────────────────────────

	private RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private RequestPostProcessor otherStudent() {
		return authentication(principalOf(otherAccountId, AccountRole.STUDENT));
	}

	/** 이 테스트가 자기 attempt 를 만든다 — POST 엔드포인트를 지나서. */
	private UUID createAttempt() throws Exception {
		String body = mockMvc.perform(post(WORKSHEET_ATTEMPTS, assignmentId).with(student()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(jsonAttemptId(body));
	}

	private static String jsonAttemptId(String body) {
		int idx = body.indexOf("\"attemptId\":\"");
		if (idx < 0) {
			throw new IllegalStateException("attemptId not present in " + body);
		}
		int start = idx + "\"attemptId\":\"".length();
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

}
