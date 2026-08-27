package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

/**
 * 학습지 목록·상세 (S2 · {@code listStudentWorksheets} · {@code getStudentWorksheet}).
 *
 * <p>🔴 <b>새 테스트 클래스인 이유</b> — {@link AttemptRepositoryIntegrationTest} 는 저장소·어댑터의
 * 순수 계약을 {@code TransactionTemplate} 로 직접 실증한다. 이 테스트는 <b>MockMvc + 보안 체인</b>
 * (인증·guard·resolver) 위에서 응답 JSON 을 단정한다. 시험하는 표면과 도구가 달라 한 클래스에
 * 섞으면 셋업이 어긋난다(예: {@code @AutoConfigureMockMvc}). NEXT.md §3 「함부로 늘리지 마라」의
 * 유일한 예외로 남긴다.</p>
 *
 * <p>🔴 <b>제한 역할</b> 위에서 돈다 — {@link MembershipRlsEnforcedSupport}. superuser 로 돌면
 * {@code problem_assignments} · {@code member_attempts} 정책이 통째로 우회돼 「남의 학습지 격리」
 * 단언이 아무것도 증명하지 못한다(MB-34).</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class StudentWorksheetIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String WORKSHEETS = "/api/v1/member/students/me/worksheets";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;

	private UUID teacherId;
	private UUID studentAccountId;
	private UUID studentId;
	private UUID otherStudentAccountId;
	private UUID otherStudentId;
	private UUID problemSetId;
	private UUID assignmentId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		clearFixtures(admin);
		now = OffsetDateTime.now();

		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);

		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccountId, "박학생", null, now);
		activate(studentId);

		otherStudentAccountId = insertAccount(admin, "other@example.com", "STUDENT", now);
		otherStudentId = MemberPostgresSupport.insertStudentProfile(
			admin, otherStudentAccountId, "이학생", null, now);
		activate(otherStudentId);

		// 🔴 problem_generation_requests 의 트리거가 「STUDENT 타깃은 활성 강사 관계가 있어야 한다」
		//    를 강제한다(V13/V33 계열). 학생 학습지를 만들려면 반드시 이 행이 먼저 있어야 한다.
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);

		UUID requestId = insertProblemRequest(teacherId, studentId);
		problemSetId = insertProblemSet(teacherId, requestId);
		UUID itemA = insertProblemItem(teacherId, requestId, 1);
		UUID itemB = insertProblemItem(teacherId, requestId, 2);
		insertSavedItem(teacherId, requestId, problemSetId, itemA, 1);
		insertSavedItem(teacherId, requestId, problemSetId, itemB, 2);
		assignmentId = insertAssignment(teacherId, requestId, problemSetId, studentId);

		rateLimiter.resetForTesting();
		rateLimiter.overridePermitsForTesting(1000);
	}

	@AfterEach
	void tearDown() {
		rateLimiter.resetForTesting();
	}

	// ─────────────────────────── 전제 ───────────────────────────

	@Test
	@DisplayName("🔴 전제 — 애플리케이션 커넥션이 RLS 대상이다 (false/false)")
	void appConnectionIsSubjectToRls() {
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");
	}

	// ─────────────────────────── list ───────────────────────────

	@Test
	@DisplayName("목록 — 자기 학습지 1건이 status=NEW · itemCount=2 로 나온다")
	void listReturnsOwnAssignment() throws Exception {
		mockMvc.perform(get(WORKSHEETS).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].assignmentId").value(assignmentId.toString()))
			.andExpect(jsonPath("$.data.items[0].itemCount").value(2))
			.andExpect(jsonPath("$.data.items[0].status").value("NEW"))
			.andExpect(jsonPath("$.data.items[0].teacher.teacherId").value(teacherId.toString()))
			.andExpect(jsonPath("$.data.items[0].teacher.displayName").value("김강사"))
			// 🔴 지어내지 않는 값들 (nullable → 키는 있고 값은 null)
			.andExpect(jsonPath("$.data.items[0].areaTag").isEmpty())
			.andExpect(jsonPath("$.data.items[0].accuracyRate").isEmpty())
			.andExpect(jsonPath("$.data.items[0].latestAttemptId").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.nextCursor").isEmpty());
	}

	@Test
	@DisplayName("🔴 목록 — 다른 학생 컨텍스트에서는 남의 학습지가 0건이다 (RLS)")
	void listIsolatesAcrossStudents() throws Exception {
		mockMvc.perform(get(WORKSHEETS).with(otherStudent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0));
	}

	@Test
	@DisplayName("목록 — IN_PROGRESS attempt 가 있으면 status=IN_PROGRESS 로 파생된다")
	void listDerivesInProgressStatus() throws Exception {
		insertAttempt(assignmentId, "IN_PROGRESS");
		mockMvc.perform(get(WORKSHEETS).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data.items[0].latestAttemptId").isNotEmpty());
	}

	@Test
	@DisplayName("목록 — SCORED attempt 가 있으면 status=COMPLETED 로 파생된다")
	void listDerivesCompletedStatus() throws Exception {
		insertAttempt(assignmentId, "SCORED");
		mockMvc.perform(get(WORKSHEETS).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"));
	}

	@Test
	@DisplayName("목록 — status 필터가 유효하지 않으면 400 이다 (조용히 무시하지 않는다)")
	void listRejectsInvalidStatusFilter() throws Exception {
		mockMvc.perform(get(WORKSHEETS).param("status", "BOGUS").with(student()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("목록 — limit > 50 은 400 이다 (조용히 깎지 않는다)")
	void listRejectsLimitOverMax() throws Exception {
		mockMvc.perform(get(WORKSHEETS).param("limit", "51").with(student()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("목록 — 깨진 cursor 는 400 이다 (조용히 첫 페이지로 되돌리지 않는다)")
	void listRejectsMalformedCursor() throws Exception {
		String malformed = Base64.getUrlEncoder().withoutPadding()
			.encodeToString("not-a-cursor".getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(get(WORKSHEETS).param("cursor", malformed).with(student()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("목록 — 배정 0건은 200 + items:[] · hasNext=false")
	void listIsEmptyWithoutAssignments() throws Exception {
		mockMvc.perform(get(WORKSHEETS).with(otherStudent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isArray())
			.andExpect(jsonPath("$.data.items.length()").value(0))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	@DisplayName("목록 — limit=1 로 페이지가 갈리면 hasNext=true · nextCursor 가 채워진다")
	void listPaginatesWithCursor() throws Exception {
		// 2번째 assignment 를 만들어 페이지가 갈리게 한다.
		UUID requestId2 = insertProblemRequest(teacherId, studentId);
		UUID setId2 = insertProblemSet(teacherId, requestId2);
		UUID item = insertProblemItem(teacherId, requestId2, 1);
		insertSavedItem(teacherId, requestId2, setId2, item, 1);
		UUID assignment2 = insertAssignment(teacherId, requestId2, setId2, studentId);

		String nextCursor = mockMvc.perform(get(WORKSHEETS)
				.param("limit", "1").with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
			.andReturn().getResponse().getContentAsString();

		// 두 assignment 모두 유효했다는 것을 확인 (nextCursor 를 그대로 넣으면 두 번째가 나온다)
		String cursorValue = extractNextCursor(nextCursor);
		mockMvc.perform(get(WORKSHEETS)
				.param("limit", "1").param("cursor", cursorValue).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(false));

		assertThat(assignment2).isNotNull();
	}

	// ─────────────────────────── detail ───────────────────────────

	@Test
	@DisplayName("상세 — 자기 학습지는 200 · title 파생 · itemBreakdown 은 빈 배열")
	void detailReturnsDerivedTitleAndEmptyBreakdown() throws Exception {
		mockMvc.perform(get(WORKSHEETS + "/{id}", assignmentId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.assignmentId").value(assignmentId.toString()))
			.andExpect(jsonPath("$.data.itemCount").value(2))
			.andExpect(jsonPath("$.data.title").exists())
			// 🔴 원천 태그 미보임 (MB-41) — 빈 배열이 정본이다
			.andExpect(jsonPath("$.data.itemBreakdown").isArray())
			.andExpect(jsonPath("$.data.itemBreakdown.length()").value(0));
	}

	@Test
	@DisplayName("🔴 상세 — 남의 학습지는 404 다 (권한 없음과 부재를 구분하지 않는다)")
	void detailIsNotFoundForForeignAssignment() throws Exception {
		mockMvc.perform(get(WORKSHEETS + "/{id}", assignmentId).with(otherStudent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("상세 — 존재하지 않는 UUID 도 404")
	void detailIsNotFoundForUnknownId() throws Exception {
		mockMvc.perform(get(WORKSHEETS + "/{id}", UUID.randomUUID()).with(student()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("상세 — UUID 형식이 아니면 400 다")
	void detailRejectsMalformedId() throws Exception {
		mockMvc.perform(get(WORKSHEETS + "/not-a-uuid").with(student()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("인증 없이 부르면 401 이다")
	void anonymousIsUnauthorized() throws Exception {
		mockMvc.perform(get(WORKSHEETS)).andExpect(status().isUnauthorized());
	}

	// ─────────────────────────── 헬퍼 ───────────────────────────

	private RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private RequestPostProcessor otherStudent() {
		return authentication(principalOf(otherStudentAccountId, AccountRole.STUDENT));
	}

	/**
	 * {@code member_student_activation} 을 {@code ACTIVE} 로 넣는다.
	 * {@link MemberPostgresSupport#insertStudentProfile} 는 profile 만 만들고 활성화 행은
	 * 만들지 않는다(설계 상 상위 헬퍼가 책임). 이 테스트는 guard 통과가 목적이라 여기서 붙인다.
	 */
	private void activate(UUID id) {
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?, ?)",
			id, now, now, now);
	}

	private UUID insertProblemRequest(UUID teacher, UUID student) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_requests (id, teacher_id, tenant_alias,"
			+ " target_kind, student_id, target_ref, ai_idempotency_key, snapshot_hash,"
			+ " request_payload, status, requested_at, updated_at)"
			+ " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)",
			id, teacher, "tn_" + hex(), student, "st_" + hex(),
			"pg_" + hex(), "sha256:" + hex() + hex(), now, now);
		return id;
	}

	private UUID insertProblemSet(UUID teacher, UUID requestId) {
		UUID setId = UUID.randomUUID();
		admin.update("INSERT INTO saved_problem_sets (id, teacher_id, problem_request_id, status,"
			+ " saved_at, updated_at) VALUES (?, ?, ?, 'SAVED', ?, ?)",
			setId, teacher, requestId, now, now);
		return setId;
	}

	private UUID insertProblemItem(UUID teacher, UUID requestId, int ordinal) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_items (id, teacher_id, problem_request_id,"
			+ " ordinal, stem, validation_status, raw_payload, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?, '문항', 'PASSED', '{}'::jsonb, ?, ?)",
			id, teacher, requestId, ordinal, now, now);
		return id;
	}

	private void insertSavedItem(UUID teacher, UUID requestId, UUID setId, UUID itemId, int ord) {
		String snapshot = String.format(
			"{\"itemId\":\"%s\",\"ordinal\":%d,\"stem\":\"본문\",\"correctNo\":1,"
				+ "\"options\":[{\"position\":1,\"content\":\"보기\"}]}",
			itemId, ord);
		admin.update("INSERT INTO saved_problem_set_items (problem_set_id, item_id, teacher_id,"
			+ " problem_request_id, ordinal, item_snapshot) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
			setId, itemId, teacher, requestId, ord, snapshot);
	}

	private UUID insertAssignment(UUID teacher, UUID requestId, UUID setId, UUID student) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_assignments (id, teacher_id, problem_request_id,"
			+ " problem_set_id, student_id, status, published_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?)",
			id, teacher, requestId, setId, student, now);
		return id;
	}

	private void insertAttempt(UUID assignment, String status) {
		String hash = "sha256:" + hex() + hex();
		java.time.Instant startedAt = java.time.Instant.now();
		java.sql.Timestamp submittedAt = "IN_PROGRESS".equals(status) ? null
			: java.sql.Timestamp.from(startedAt.plusSeconds(60));
		java.sql.Timestamp scoredAt = "SCORED".equals(status) ? submittedAt : null;
		admin.update("INSERT INTO member_attempts (id, student_id, assignment_id, teacher_id,"
			+ " status, snapshot_hash, item_count, started_at, submitted_at, scored_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, 2, ?, ?, ?)",
			UUID.randomUUID(), studentId, assignment, teacherId,
			status, hash, java.sql.Timestamp.from(startedAt),
			submittedAt, scoredAt);
	}

	private static String hex() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static String extractNextCursor(String responseBody) {
		int idx = responseBody.indexOf("\"nextCursor\":\"");
		if (idx < 0) {
			throw new IllegalStateException("nextCursor not present in " + responseBody);
		}
		int start = idx + "\"nextCursor\":\"".length();
		int end = responseBody.indexOf('"', start);
		return responseBody.substring(start, end);
	}
}
