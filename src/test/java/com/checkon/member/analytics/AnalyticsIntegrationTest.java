package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

/**
 * PR7 §2·§3 분석 API 분기 검증. 정본은 {@code 01_endpoint_branch_matrix.md} — 표의 값 그대로 본다.
 *
 * <p>🔴 RLS 가 실제로 걸리는 역할 위에서 돈다({@link MembershipRlsEnforcedSupport}). superuser
 * 로 돌리면 정책이 통째로 우회돼(MB-34) 「남의 학생 → 404」 같은 단언이 무엇을 재는지 흐려진다.</p>
 *
 * <p>🔴 「200 이 나온다」로 끝내지 않고 몸통 값이 계약과 같은지도 본다. NULL 은 원문 body 문자열로
 * 확인한다({@code jsonPath doesNotExist} 는 값이 {@code null} 이어도 통과하므로 §11-3 위반).</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AnalyticsIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String STUDENT_LIST = "/api/v1/member/students/me/learning-records";
	private static final String STUDENT_DETAIL = STUDENT_LIST + "/{recordId}";
	private static final String CHILD_LIST =
		"/api/v1/member/parents/me/children/{studentId}/learning-records";
	private static final String CHILD_DETAIL = CHILD_LIST + "/{recordId}";
	private static final String CHILD_ANALYSIS =
		"/api/v1/member/parents/me/children/{studentId}/analysis";
	private static final String CHILD_WEAKNESS =
		"/api/v1/member/parents/me/children/{studentId}/analysis/weaknesses/{area}/{type}";

	private static final String MONTH = "2026-08";
	private static final String PREV_MONTH = "2026-07";
	private static final String ZONE = "Asia/Seoul";
	private static final String VERSION = "mm-1";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID otherStudentAccountId;
	private UUID otherStudentProfileId;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID unlinkedChildProfileId;
	private UUID teacherId;
	private UUID assignmentId;
	private UUID otherAssignmentId;
	private UUID sessionId;
	private UUID otherSessionId;
	private Instant sessionOccurredAt;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now(ZoneOffset.UTC);

		assertThat(applicationRolePrivileges())
			.as("전제 — 애플리케이션 역할이 false/false 여야 RLS 단언이 유효하다")
			.isEqualTo("false/false");

		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);

		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = insertStudentFull(studentAccountId, "박학생", 2);
		activate(studentProfileId);

		otherStudentAccountId = insertAccount(admin, "other-student@example.com", "STUDENT", now);
		otherStudentProfileId = insertStudentFull(otherStudentAccountId, "이학생", 2);
		activate(otherStudentProfileId);

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);

		UUID unlinkedAccountId = insertAccount(admin, "unlinked@example.com", "STUDENT", now);
		unlinkedChildProfileId = insertStudentFull(unlinkedAccountId, "남의자녀", 2);
		activate(unlinkedChildProfileId);

		linkTeacherStudent(teacherId, studentProfileId);
		linkTeacherStudent(teacherId, otherStudentProfileId);
		linkTeacherStudent(teacherId, unlinkedChildProfileId);
		linkParentStudent(parentProfileId, studentProfileId);
		linkParentTeacher(parentProfileId, teacherId);

		assignmentId = insertAssignment(teacherId, studentProfileId);
		otherAssignmentId = insertAssignment(teacherId, otherStudentProfileId);

		sessionOccurredAt = now.toInstant();
		sessionId = insertSession(assignmentId, studentProfileId, "이번주 학습지",
			5, 3, 480, sessionOccurredAt);
		otherSessionId = insertSession(otherAssignmentId, otherStudentProfileId, "남의 학습지",
			4, 2, 360, sessionOccurredAt);
	}

	// ══════════════════════ §2 학생 learning-records ══════════════════════

	@Test
	@DisplayName("student list — 정상 200, items 에 자기 세션 하나 (id·month·정확도)")
	void studentListReturnsOwnSessions() throws Exception {
		// 🔴 nextCursor 는 계약이 nullable — jsonPath doesNotExist 는 값이 null 이어도 통과하므로
		//    §11-3 위반이 된다. 원문 body 에 "nextCursor":null 이 실제로 있는지 본다.
		MvcResult result = mockMvc.perform(get(STUDENT_LIST).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(sessionId.toString()))
			.andExpect(jsonPath("$.data.items[0].month").value(MONTH))
			.andExpect(jsonPath("$.data.items[0].itemCount").value(5))
			.andExpect(jsonPath("$.data.items[0].correctCount").value(3))
			.andExpect(jsonPath("$.data.items[0].weaknessStatus").value("NO_DATA"))
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).contains("\"nextCursor\":null");
	}

	@Test
	@DisplayName("student list — month 형식 오류 → 400 INVALID_REQUEST")
	void studentListRejectsBadMonth() throws Exception {
		mockMvc.perform(get(STUDENT_LIST).param("month", "2026-8").with(student()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("🔴 student list — 미래 월 → 200 + items:[] (400 아님)")
	void studentListFutureMonthIsEmpty() throws Exception {
		String future = YearMonth.parse(MONTH).plusMonths(24).toString();
		mockMvc.perform(get(STUDENT_LIST).param("month", future).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	@DisplayName("student list — 기록 0건 → 200 + items:[]")
	void studentListZeroRecords() throws Exception {
		MemberPostgresSupport.deleteLearningSessionsForStudent(admin, studentProfileId);
		mockMvc.perform(get(STUDENT_LIST).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0));
	}

	@Test
	@DisplayName("student detail — 자기 세션 200 (weaknessStatus:NO_DATA, itemIds:[], trend:[])")
	void studentDetailReturns200() throws Exception {
		mockMvc.perform(get(STUDENT_DETAIL, sessionId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordId").value(sessionId.toString()))
			.andExpect(jsonPath("$.data.itemIds.length()").value(0))
			.andExpect(jsonPath("$.data.weaknessStatus").value("NO_DATA"))
			// 🔴 집계 행이 없으면 trend 는 빈 배열이다. 하드코딩 과거값(52/61/68)을 만들지 않는다.
			.andExpect(jsonPath("$.data.trend.length()").value(0));
	}

	@Test
	@DisplayName("🔴 student detail — trend 는 실측 월만 · 표본부족은 INSUFFICIENT + accuracyRate:null")
	void studentDetailTrendReflectsMonthlyAggregate() throws Exception {
		// 세션 월(MONTH) = AVAILABLE 3개월치 · 전월 = 표본부족 · 3개월 전 = 데이터 없음(원소 없음).
		insertStudentMetric(studentProfileId, MONTH, 20, 15, 1200);
		insertStudentMetric(studentProfileId, PREV_MONTH, 3, 2, 200);
		MvcResult result = mockMvc.perform(get(STUDENT_DETAIL, sessionId).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.trend.length()").value(2))
			// 시간순 (오름차순) — 전월 먼저, 이번달 뒤.
			.andExpect(jsonPath("$.data.trend[0].month").value(PREV_MONTH))
			.andExpect(jsonPath("$.data.trend[0].status").value("INSUFFICIENT"))
			.andExpect(jsonPath("$.data.trend[1].month").value(MONTH))
			.andExpect(jsonPath("$.data.trend[1].status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.trend[1].accuracyRate").value(0.75))
			.andReturn();
		// 🔴 표본부족 원소의 accuracyRate 는 null 이어야 한다 (0.67 로 채우면 「믿을 만한 값」의 거짓말).
		assertThat(result.getResponse().getContentAsString())
			.contains("\"month\":\"" + PREV_MONTH + "\",\"accuracyRate\":null,\"status\":\"INSUFFICIENT\"");
	}

	@Test
	@DisplayName("🔴 student detail — 남의 세션 recordId → 404 RESOURCE_NOT_FOUND")
	void studentDetailForeignSessionIs404() throws Exception {
		mockMvc.perform(get(STUDENT_DETAIL, otherSessionId).with(student()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("student detail — 없는 UUID → 404")
	void studentDetailMissingIs404() throws Exception {
		mockMvc.perform(get(STUDENT_DETAIL, UUID.randomUUID()).with(student()))
			.andExpect(status().isNotFound());
	}

	// ══════════════════════ §3 학부모 learning-records ══════════════════════

	@Test
	@DisplayName("parent list — 자녀 세션 200 + items 하나")
	void parentListReturnsChildSessions() throws Exception {
		mockMvc.perform(get(CHILD_LIST, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(sessionId.toString()));
	}

	@Test
	@DisplayName("🔴 parent list — 연결 안 된 자녀 → 404 (자녀 연결 검증이 먼저)")
	void parentListUnlinkedChildIs404() throws Exception {
		mockMvc.perform(get(CHILD_LIST, unlinkedChildProfileId).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 parent detail — 연결 안 된 자녀의 recordId → 404 (관계 검증 먼저)")
	void parentDetailUnlinkedChildIs404() throws Exception {
		mockMvc.perform(get(CHILD_DETAIL, unlinkedChildProfileId, sessionId).with(parent()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("parent detail — 자녀 세션 200")
	void parentDetailReturns200() throws Exception {
		mockMvc.perform(get(CHILD_DETAIL, studentProfileId, sessionId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordId").value(sessionId.toString()));
	}

	// ══════════════════════ §3 학부모 analysis ══════════════════════

	@Test
	@DisplayName("🔴 parent analysis — month 누락 → 400")
	void parentAnalysisMonthMissing() throws Exception {
		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).with(parent()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("parent analysis — month 형식 오류 → 400")
	void parentAnalysisMonthFormat() throws Exception {
		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).param("month", "2026/08")
				.with(parent()))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("🔴 parent analysis — 미래 월 → 200 + overall.status:NO_DATA (400 아님)")
	void parentAnalysisFutureMonth() throws Exception {
		String future = YearMonth.parse(MONTH).plusMonths(24).toString();
		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", future).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.status").value("NO_DATA"))
			.andExpect(jsonPath("$.data.weaknessRanking.length()").value(0))
			.andReturn();
		// 🔴 값 null 을 확인할 때 doesNotExist 는 위장이 된다 — 원문에 nulls 가 있는지 본다(§11-3).
		assertOverallNullFields(result);
	}

	@Test
	@DisplayName("🔴 parent analysis — 집계 미생성 → 200 + status:NO_DATA + calculatedAt:null")
	void parentAnalysisNoAggregate() throws Exception {
		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.status").value("NO_DATA"))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("\"calculatedAt\":null");
	}

	@Test
	@DisplayName("parent analysis — 정상 AVAILABLE (accuracy 계산)")
	void parentAnalysisAvailable() throws Exception {
		insertStudentMetric(studentProfileId, MONTH, 20, 15, 1200);
		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.overall.scoredCount").value(20))
			.andExpect(jsonPath("$.data.overall.averageActiveSeconds").value(60));
	}

	@Test
	@DisplayName("🔴 parent analysis — overall 표본 부족 → status:INSUFFICIENT + accuracyRate:null")
	void parentAnalysisOverallInsufficient() throws Exception {
		// scored=3 < minimumSampleSize(=10). scored=0 이 아니라 진짜 표본 부족 분기여야 한다.
		insertStudentMetric(studentProfileId, MONTH, 3, 2, 200);
		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.status").value("INSUFFICIENT"))
			.andExpect(jsonPath("$.data.overall.scoredCount").value(3))
			.andReturn();
		// 🔴 accuracyRate 를 0.4 나 0 으로 채우면 「신뢰 가능한 값」이라는 거짓말이 된다.
		//    원문에 "accuracyRate":null 로 있는지 본다(§11-3 · jsonPath 는 null 도 통과).
		assertThat(result.getResponse().getContentAsString())
			.contains("\"accuracyRate\":null");
	}

	@Test
	@DisplayName("🔴 parent analysis — 전월 없음 → improvement.status:NO_PREVIOUS_PERIOD, delta:null")
	void parentAnalysisNoPreviousPeriod() throws Exception {
		insertStudentMetric(studentProfileId, MONTH, 15, 9, 900);
		insertWeaknessMetric(studentProfileId, MONTH, "reading", "fact", 15, 9);
		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.weaknessRanking[0].improvement.status")
				.value("NO_PREVIOUS_PERIOD"))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("\"accuracyDeltaPp\":null");
		assertThat(body).contains("\"previousAccuracyRate\":null");
	}

	@Test
	@DisplayName("🔴 parent analysis — 표본 부족 → improvement.status:INSUFFICIENT_SAMPLE")
	void parentAnalysisInsufficientSample() throws Exception {
		// 최소 표본(default=10) 밑. 이번 달·전월 다 있어야 표본 부족 분기로 간다.
		insertStudentMetric(studentProfileId, MONTH, 3, 2, 200);
		insertWeaknessMetric(studentProfileId, MONTH, "reading", "fact", 3, 2);
		insertStudentMetric(studentProfileId, PREV_MONTH, 3, 1, 200);
		insertWeaknessMetric(studentProfileId, PREV_MONTH, "reading", "fact", 3, 1);
		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.weaknessRanking[0].improvement.status")
				.value("INSUFFICIENT_SAMPLE"))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("\"accuracyDeltaPp\":null");
	}

	@Test
	@DisplayName("🔴 parent analysis — 태그 없는 문항만 → weaknessRanking:[], primaryWeakness:null")
	void parentAnalysisNoTaggedItems() throws Exception {
		insertStudentMetric(studentProfileId, MONTH, 20, 12, 1000);
		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.weaknessRanking.length()").value(0))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("\"primaryWeakness\":null");
	}

	// ══════════════════════ §3 학부모 weakness detail ══════════════════════

	@Test
	@DisplayName("weakness detail — 정상 200 (셀 값 · improvement)")
	void weaknessDetailReturns200() throws Exception {
		insertWeaknessMetric(studentProfileId, MONTH, "reading", "fact", 12, 8);
		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "reading", "fact")
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.areaTag").value("reading"))
			.andExpect(jsonPath("$.data.typeTag").value("fact"))
			.andExpect(jsonPath("$.data.status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.scoredCount").value(12))
			.andExpect(jsonPath("$.data.correctCount").value(8))
			.andExpect(jsonPath("$.data.recentItems.length()").value(0))
			.andExpect(jsonPath("$.data.truncated.applied").value(false));
	}

	@Test
	@DisplayName("🔴 weakness detail — 태그 5종 밖 → 400 INVALID_REQUEST")
	void weaknessDetailBadArea() throws Exception {
		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "not_an_area", "fact")
				.param("month", MONTH).with(parent()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("🔴 weakness detail — 대문자 CONCEPT → 400 (소문자가 정본)")
	void weaknessDetailUppercaseIs400() throws Exception {
		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "reading", "CONCEPT")
				.param("month", MONTH).with(parent()))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("🔴 weakness detail — 셀 데이터 없음 → 200 + status:NO_DATA + recentItems:[]")
	void weaknessDetailNoData() throws Exception {
		MvcResult result = mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "media", "infer")
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("NO_DATA"))
			.andExpect(jsonPath("$.data.recentItems.length()").value(0))
			.andExpect(jsonPath("$.data.scoredCount").value(0))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		// 데이터 없음일 때 accuracyRate 는 null 이 유일한 정직한 값이다. 0.0 이면 「완전 오답」이라는 거짓말.
		assertThat(body).contains("\"accuracyRate\":null");
	}

	// ══════════════════════ 헬퍼 ══════════════════════

	private static void assertOverallNullFields(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		// NO_DATA 인데 accuracyRate·scoredCount·averageActiveSeconds 가 0 이나 정수로 채워지면 §11-3 위반.
		assertThat(body).contains("\"accuracyRate\":null");
		assertThat(body).contains("\"scoredCount\":null");
		assertThat(body).contains("\"averageActiveSeconds\":null");
	}

	private UUID insertStudentFull(UUID accountId, String name, int grade) {
		UUID profileId = MemberPostgresSupport.insertStudentProfile(
			admin, accountId, name, grade, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name,"
			+ " created_at, updated_at) VALUES (?, ?, ?, ?)", accountId, name, now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", profileId, "STU-" + upperHex(6), now);
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'PENDING_PARENT_LINK', NULL, ?, ?)",
			profileId, now, now);
		return profileId;
	}

	private void activate(UUID profileId) {
		admin.update("UPDATE member_student_activation SET status = 'ACTIVE', activated_at = ?"
			+ " WHERE student_id = ?", now, profileId);
	}

	private void linkTeacherStudent(UUID teacher, UUID studentId) {
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacher, studentId, now, now);
	}

	private void linkParentStudent(UUID parent, UUID studentId) {
		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parent, studentId, now, now);
	}

	private void linkParentTeacher(UUID parent, UUID teacher) {
		admin.update("INSERT INTO parent_teacher_relationships"
			+ " (id, parent_id, teacher_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parent, teacher, now, now);
	}

	private UUID insertAssignment(UUID teacher, UUID student) {
		UUID requestId = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_requests"
			+ " (id, teacher_id, tenant_alias, target_kind, student_id, target_ref,"
			+ "  ai_idempotency_key, snapshot_hash, request_payload, status,"
			+ "  requested_at, updated_at)"
			+ " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)",
			requestId, teacher, "tn_" + hex(32), student, "st_" + hex(32),
			"pg_" + hex(32), "sha256:" + hex(32) + hex(32), now, now);
		UUID setId = UUID.randomUUID();
		admin.update("INSERT INTO saved_problem_sets"
			+ " (id, teacher_id, problem_request_id, status, saved_at, updated_at)"
			+ " VALUES (?, ?, ?, 'SAVED', ?, ?)",
			setId, teacher, requestId, now, now);
		UUID assignment = UUID.randomUUID();
		admin.update("INSERT INTO problem_assignments"
			+ " (id, teacher_id, problem_request_id, problem_set_id, student_id, status,"
			+ "  published_at) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?)",
			assignment, teacher, requestId, setId, student, now);
		return assignment;
	}

	private UUID insertSession(
		UUID assignment, UUID studentId, String title,
		int itemCount, int correctCount, int activeSec, Instant occurredAt
	) {
		UUID attemptId = UUID.randomUUID();
		UUID teacherOfAssignment = admin.queryForObject(
			"SELECT teacher_id FROM problem_assignments WHERE id = ?", UUID.class, assignment);
		admin.update("INSERT INTO member_attempts"
			+ " (id, student_id, assignment_id, teacher_id, status, version, snapshot_hash,"
			+ "  item_count, active_elapsed_sec, last_client_sequence, started_at,"
			+ "  last_progress_at, submitted_at, scored_at)"
			+ " VALUES (?, ?, ?, ?, 'SCORED', 0, ?, ?, ?, NULL, ?, ?, ?, ?)",
			attemptId, studentId, assignment, teacherOfAssignment,
			"sha256:" + "0".repeat(64), itemCount, activeSec, now, now, now, now);
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO member_learning_sessions"
			+ " (id, attempt_id, student_id, teacher_id, assignment_id, title_text,"
			+ "  item_count, correct_count, active_elapsed_sec, submit_record_id, occurred_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
			id, attemptId, studentId, teacherOfAssignment, assignment, title,
			itemCount, correctCount, activeSec,
			OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
		return id;
	}

	private void insertStudentMetric(
		UUID studentId, String month, int scored, int correct, int totalSec
	) {
		insertStudentMetric(teacherId, studentId, month, scored, correct, totalSec);
	}

	private void insertStudentMetric(
		UUID teacher, UUID studentId, String month, int scored, int correct, int totalSec
	) {
		admin.update("INSERT INTO member_monthly_student_metrics"
			+ " (teacher_id, student_id, month, month_zone, scored_count, correct_count,"
			+ "  total_active_sec, calculation_version, calculated_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			teacher, studentId, month, ZONE, scored, correct, totalSec, VERSION, now);
	}

	private void insertWeaknessMetric(
		UUID studentId, String month, String area, String type, int scored, int correct
	) {
		insertWeaknessMetric(teacherId, studentId, month, area, type, scored, correct);
	}

	private void insertWeaknessMetric(
		UUID teacher, UUID studentId, String month, String area, String type,
		int scored, int correct
	) {
		admin.update("INSERT INTO member_monthly_weakness_metrics"
			+ " (teacher_id, student_id, month, month_zone, area_tag, type_tag,"
			+ "  scored_count, correct_count, status, calculation_version, calculated_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?)",
			teacher, studentId, month, ZONE, area, type, scored, correct, VERSION, now);
	}

	private static String hex(int chars) {
		StringBuilder builder = new StringBuilder(chars);
		String source = UUID.randomUUID().toString().replace("-", "");
		while (builder.length() < chars) {
			builder.append(source);
		}
		return builder.substring(0, chars);
	}

	private static String upperHex(int chars) {
		return hex(chars).toUpperCase();
	}

	// ══════════════ teacherId 필터 — 계약 :1538-1540 (MB-60) ══════════════

	/**
	 * 🔴 <b>「200 이 나온다」로 끝내면 필터가 안 걸려도 통과한다.</b> 두 강사에게 서로 다른
	 * 집계를 심고 <b>세 값이 실제로 다른지</b> 본다. 값은 전부 나누어떨어지게 골랐다 —
	 * 떨어지지 않으면 scale 10 반올림과 double 비교가 어긋나 무엇을 재는지 흐려진다.
	 */
	@Test
	@DisplayName("🔴 parent analysis — teacherId 가 합산을 실제로 좁힌다")
	void parentAnalysisTeacherFilterNarrowsAggregate() throws Exception {
		UUID second = insertSecondTeacher();
		// A: 30문항 15정답 = 0.5 / B: 10문항 9정답 = 0.9 / 합산: 40문항 24정답 = 0.6
		insertStudentMetric(teacherId, studentProfileId, MONTH, 30, 15, 1800);
		insertStudentMetric(second, studentProfileId, MONTH, 10, 9, 600);

		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).param("month", MONTH)
				.with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.scoredCount").value(40))
			.andExpect(jsonPath("$.data.overall.accuracyRate").value(0.6));

		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).param("month", MONTH)
				.param("teacherId", teacherId.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.scoredCount").value(30))
			.andExpect(jsonPath("$.data.overall.accuracyRate").value(0.5));

		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).param("month", MONTH)
				.param("teacherId", second.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.scoredCount").value(10))
			.andExpect(jsonPath("$.data.overall.accuracyRate").value(0.9));
	}

	@Test
	@DisplayName("🔴 parent analysis — 그 강사 데이터 0건이면 NO_DATA + null (0 아님)")
	void parentAnalysisTeacherFilterWithoutDataIsNoData() throws Exception {
		UUID second = insertSecondTeacher();
		insertStudentMetric(teacherId, studentProfileId, MONTH, 30, 15, 1800);

		MvcResult result = mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId)
				.param("month", MONTH).param("teacherId", second.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall.status").value("NO_DATA"))
			.andReturn();
		assertThat(result.getResponse().getContentAsString())
			.as("데이터가 없는데 0 으로 채웠다")
			.doesNotContain("\"scoredCount\":0", "\"accuracyRate\":0.");
		assertOverallNullFields(result);
	}

	@Test
	@DisplayName("🔴 weakness 상세 — teacherId 가 셀을 실제로 좁힌다")
	void weaknessDetailTeacherFilterNarrowsCell() throws Exception {
		UUID second = insertSecondTeacher();
		// A: 20문항 10정답 = 0.5 / B: 10문항 9정답 = 0.9 / 합산: 30문항
		insertWeaknessMetric(teacherId, studentProfileId, MONTH, "reading", "fact", 20, 10);
		insertWeaknessMetric(second, studentProfileId, MONTH, "reading", "fact", 10, 9);

		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "reading", "fact")
				.param("month", MONTH).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scoredCount").value(30));

		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "reading", "fact")
				.param("month", MONTH).param("teacherId", teacherId.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scoredCount").value(20))
			.andExpect(jsonPath("$.data.accuracyRate").value(0.5));

		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "reading", "fact")
				.param("month", MONTH).param("teacherId", second.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scoredCount").value(10))
			.andExpect(jsonPath("$.data.accuracyRate").value(0.9));
	}

	@Test
	@DisplayName("🔴 parent learning-records — teacherId 가 목록을 실제로 좁힌다")
	void parentRecordsTeacherFilterNarrowsList() throws Exception {
		UUID second = insertSecondTeacher();
		UUID secondAssignment = insertAssignment(second, studentProfileId);
		insertSession(secondAssignment, studentProfileId, "다른 강사 학습지",
			3, 1, 200, sessionOccurredAt.minusSeconds(60));

		mockMvc.perform(get(CHILD_LIST, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2));
		mockMvc.perform(get(CHILD_LIST, studentProfileId)
				.param("teacherId", teacherId.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(sessionId.toString()));
		mockMvc.perform(get(CHILD_LIST, studentProfileId)
				.param("teacherId", second.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].title").value("다른 강사 학습지"));
	}

	@Test
	@DisplayName("🔴 세 경로 모두 — teacherId 가 관계 교집합 밖이면 404")
	void teacherFilterOutsideIntersectionIsNotFound() throws Exception {
		// 🔴 student↔teacher 는 있는데 parent↔teacher 가 없는 강사 — 교집합 밖이다.
		UUID halfLinked = insertTeacher(admin, "half@example.com", "반강사", now);
		linkTeacherStudent(halfLinked, studentProfileId);

		mockMvc.perform(get(CHILD_LIST, studentProfileId)
				.param("teacherId", halfLinked.toString()).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
		mockMvc.perform(get(CHILD_ANALYSIS, studentProfileId).param("month", MONTH)
				.param("teacherId", halfLinked.toString()).with(parent()))
			.andExpect(status().isNotFound());
		mockMvc.perform(get(CHILD_WEAKNESS, studentProfileId, "reading", "fact")
				.param("month", MONTH).param("teacherId", halfLinked.toString())
				.with(parent()))
			.andExpect(status().isNotFound());
	}

	/** 자녀·학부모 둘 다와 연결된 둘째 강사. 교집합 안이라 필터로 쓸 수 있다. */
	private UUID insertSecondTeacher() {
		UUID second = insertTeacher(admin, "second@example.com", "이강사", now);
		linkTeacherStudent(second, studentProfileId);
		linkParentTeacher(parentProfileId, second);
		return second;
	}

	private RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}
}
