package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
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
 * 분기표 §4 의 <b>자녀 홈</b> 6행을 1:1 로 덮는다
 * ({@code 01_endpoint_branch_matrix.md} — 표의 값 그대로 본다).
 *
 * <p>🔴 이 엔드포인트는 계약(<code>member-api.yaml:887</code>)과 분기표에 여섯 PR 동안
 * 있었는데 <b>컨트롤러가 없었다.</b> 게이트가 「분기표 행마다 테스트」는 보지만
 * <b>엔드포인트 존재는 안 봐서</b> 안 걸렸다. 이 스위트가 그 자리를 메운다.</p>
 *
 * <p>🔴 RLS 가 실제로 걸리는 역할 위에서 돈다({@link MembershipRlsEnforcedSupport}).
 * superuser 로 돌리면 정책이 통째로 우회돼(MB-34) 「남의 자녀 → 404」가 무엇을 재는지 흐려진다.</p>
 *
 * <p>🔴 {@code null} 판정은 <b>원문 body 문자열</b>로 한다 —
 * {@code jsonPath().doesNotExist()} 는 값이 {@code null} 이어도 통과하므로 §11-3 위반이다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ParentHomeIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String HOME =
		"/api/v1/member/parents/me/children/{studentId}/home";
	private static final String ZONE = "Asia/Seoul";
	private static final String VERSION = "mm-1";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private String currentMonth;

	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID otherParentAccountId;
	private UUID studentProfileId;
	private UUID studentAccountId;
	private UUID endedChildProfileId;
	private UUID unlinkedChildProfileId;
	private UUID linkedTeacherId;
	private UUID strangerTeacherId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now(ZoneOffset.UTC);
		// 🔴 서버가 정하는 달과 같은 기준으로 계산한다. 「2026-08」을 박으면 달이 넘어가는
		//    순간 이 스위트가 통째로 깨진다(하드코딩된 과거값 금지).
		currentMonth = YearMonth.from(now.toInstant().atZone(ZoneId.of(ZONE))).toString();

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);
		otherParentAccountId = insertAccount(admin, "other-parent@example.com", "PARENT", now);
		insertParent(admin, otherParentAccountId, "이학부모", now);

		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = insertChild(studentAccountId, "박학생", "STU-AAAAAA");
		endedChildProfileId = insertChild(
			insertAccount(admin, "ended@example.com", "STUDENT", now), "이학생", "STU-BBBBBB");
		unlinkedChildProfileId = insertChild(
			insertAccount(admin, "unlinked@example.com", "STUDENT", now), "최학생", "STU-CCCCCC");

		linkedTeacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		strangerTeacherId = insertTeacher(admin, "stranger@example.com", "홍강사", now);

		link(parentProfileId, studentProfileId, "ACTIVE");
		// 🔴 관계가 끝난 자녀. 행은 남아 있고 상태만 ENDED 다 — 「행이 있어도 안 보인다」를
		//    재려면 행이 실제로 있어야 한다.
		link(parentProfileId, endedChildProfileId, "ENDED");
		teacherLink(linkedTeacherId, studentProfileId);
		// strangerTeacher 는 이 자녀도 이 학부모도 모른다.
		parentTeacherLink(parentProfileId, linkedTeacherId);
	}

	// ══════════════════ 분기표 §4 · 자녀 홈 6행 ══════════════════

	@Test
	@DisplayName("🔴 전제 — 앱 커넥션이 RLS 대상이다 (super=f / bypassrls=f)")
	void applicationRoleIsSubjectToRowLevelSecurity() {
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");
	}

	@Test
	@DisplayName("① 정상 → 200. child·metrics 4종이 계약대로 내려간다")
	void normalHomeReturns200() throws Exception {
		mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.child.studentId").value(studentProfileId.toString()))
			.andExpect(jsonPath("$.data.child.studentPublicId").value("STU-AAAAAA"))
			.andExpect(jsonPath("$.data.child.name").value("박학생"))
			.andExpect(jsonPath("$.data.metrics.length()").value(4))
			.andExpect(jsonPath("$.data.metrics[0].key").value("MONTHLY_ACCURACY"))
			.andExpect(jsonPath("$.data.metrics[1].key").value("SOLVED_COUNT"))
			.andExpect(jsonPath("$.data.metrics[2].key").value("AVERAGE_DURATION_SEC"))
			.andExpect(jsonPath("$.data.metrics[3].key").value("WEAKNESS_DELTA_PP"));
	}

	@Test
	@DisplayName("② 연결 안 된 자녀 → 404 RESOURCE_NOT_FOUND")
	void unlinkedChildIsNotFound() throws Exception {
		mockMvc.perform(get(HOME, unlinkedChildProfileId).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 ③ 연결 종료(ENDED)된 자녀 → 404 (과거 열람은 MB-08 미확정 · fail-closed)")
	void endedChildIsNotFound() throws Exception {
		mockMvc.perform(get(HOME, endedChildProfileId).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
		// 🔴 전제 — 그 관계 행은 실제로 존재한다. 없어서 404 인 것이 아니다.
		assertThat(admin.queryForObject("SELECT status FROM parent_student_relationships"
			+ " WHERE parent_id = ? AND student_id = ?", String.class,
			parentProfileId, endedChildProfileId)).isEqualTo("ENDED");
	}

	@Test
	@DisplayName("🔴 ④ teacherId 필터에 관계 없음 → 404 (student↔teacher 도 parent↔teacher 도)")
	void teacherFilterOutsideIntersectionIsNotFound() throws Exception {
		// 둘 다 없는 강사.
		mockMvc.perform(get(HOME, studentProfileId)
			.param("teacherId", strangerTeacherId.toString()).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

		// 🔴 student↔teacher 는 있는데 parent↔teacher 가 없는 강사 — 교집합 밖이다.
		UUID halfLinked = insertTeacher(admin, "half@example.com", "반강사", now);
		teacherLink(halfLinked, studentProfileId);
		mockMvc.perform(get(HOME, studentProfileId)
			.param("teacherId", halfLinked.toString()).with(parent()))
			.andExpect(status().isNotFound());

		// 🔴 parent↔teacher 는 있는데 student↔teacher 가 없는 강사 — 역시 교집합 밖이다.
		UUID parentOnly = insertTeacher(admin, "parent-only@example.com", "학부모강사", now);
		parentTeacherLink(parentProfileId, parentOnly);
		mockMvc.perform(get(HOME, studentProfileId)
			.param("teacherId", parentOnly.toString()).with(parent()))
			.andExpect(status().isNotFound());

		// 교집합 안이면 200 이다 — 필터가 통째로 죽은 것이 아니다.
		mockMvc.perform(get(HOME, studentProfileId)
			.param("teacherId", linkedTeacherId.toString()).with(parent()))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("🔴 ⑤ 지표 산출 불가 → 200 + status:NO_DATA, value:null (0 으로 채우지 않는다)")
	void metricsWithoutDataAreNoData() throws Exception {
		MvcResult result = mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.metrics[0].status").value("NO_DATA"))
			.andExpect(jsonPath("$.data.metrics[1].status").value("NO_DATA"))
			.andExpect(jsonPath("$.data.metrics[2].status").value("NO_DATA"))
			.andExpect(jsonPath("$.data.metrics[3].status").value("NO_DATA"))
			.andReturn();
		// 🔴 doesNotExist 는 값이 null 이어도 통과한다. 원문으로 본다(§11-3).
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("\"status\":\"NO_DATA\",\"value\":null,\"unit\":null");
		assertThat(body).as("값이 없는데 0 으로 채웠다").doesNotContain("\"value\":0");
	}

	@Test
	@DisplayName("🔴 ⑥ 발행 보고서 없음 → 200 + latestReport 키가 있고 값이 null")
	void latestReportIsNullWhenNonePublished() throws Exception {
		MvcResult result = mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk()).andReturn();
		// 🔴 계약이 nullable 이라 **키를 두고 값을 null** 이어야 한다.
		assertThat(result.getResponse().getContentAsString())
			.contains("\"latestReport\":null");
	}

	// ══════════════════ 값이 있을 때 ══════════════════

	@Test
	@DisplayName("지표 — 집계가 있으면 AVAILABLE + 값. 표본 부족이면 개수만 AVAILABLE")
	void metricsReflectMonthlyAggregate() throws Exception {
		insertStudentMetric(20, 15, 1200);
		mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.metrics[0].status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.metrics[0].value").value(0.75))
			.andExpect(jsonPath("$.data.metrics[0].unit").value("RATIO"))
			.andExpect(jsonPath("$.data.metrics[1].value").value(20))
			.andExpect(jsonPath("$.data.metrics[1].unit").value("COUNT"))
			.andExpect(jsonPath("$.data.metrics[2].value").value(60))
			.andExpect(jsonPath("$.data.metrics[2].unit").value("SECONDS"));
	}

	@Test
	@DisplayName("🔴 지표 — 표본 부족이면 정확도는 INSUFFICIENT + null, 개수는 AVAILABLE")
	void insufficientSampleKeepsCountButNotRate() throws Exception {
		insertStudentMetric(3, 2, 300);
		MvcResult result = mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.metrics[0].status").value("INSUFFICIENT"))
			.andExpect(jsonPath("$.data.metrics[1].status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.metrics[1].value").value(3))
			.andReturn();
		// 🔴 믿을 수 없는 비율을 내려보내면 「측정했다」는 거짓말이 된다.
		assertThat(result.getResponse().getContentAsString())
			.contains("\"key\":\"MONTHLY_ACCURACY\",\"status\":\"INSUFFICIENT\",\"value\":null");
	}

	@Test
	@DisplayName("🔴 latestReport 는 발행된 것만이다 — 미발행은 새지 않는다")
	void latestReportShowsOnlyPublished() throws Exception {
		insertReport("DRAFT", 1);
		MvcResult draftOnly = mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk()).andReturn();
		assertThat(draftOnly.getResponse().getContentAsString())
			.as("미발행 보고서가 홈에 새어 나왔다")
			.contains("\"latestReport\":null");

		UUID published = insertReport("PUBLISHED", 2);
		mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.latestReport.reportId").value(published.toString()))
			.andExpect(jsonPath("$.data.latestReport.status").value("PUBLISHED"));
	}

	@Test
	@DisplayName("🔴 남의 학부모는 이 자녀의 홈을 못 본다 → 404")
	void otherParentCannotReadHome() throws Exception {
		mockMvc.perform(get(HOME, studentProfileId).with(otherParent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 응답 어디에도 전국 백분위가 없다")
	void noNationalPercentileField() throws Exception {
		MvcResult result = mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk()).andReturn();
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("percentile", "Percentile");
	}

	@Test
	@DisplayName("teacherId 를 생략하면 관계 검증만 하고 200 이다")
	void teacherFilterIsOptional() throws Exception {
		mockMvc.perform(get(HOME, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recentRecords").isArray());
	}

	// ──────────────────────────── 도우미 ────────────────────────────

	private UUID insertChild(UUID accountId, String displayName, String publicId) {
		UUID profileId = MemberPostgresSupport.insertStudentProfile(
			admin, accountId, displayName, 2, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", accountId, displayName, now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", profileId, publicId, now);
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?, ?)", profileId, now, now, now);
		return profileId;
	}

	private void link(UUID parentId, UUID studentId, String status) {
		OffsetDateTime endedAt = "ENDED".equals(status) ? now : null;
		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, ended_at, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(), parentId, studentId, status, now, endedAt, now);
	}

	private void teacherLink(UUID teacherId, UUID studentId) {
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);
	}

	private void parentTeacherLink(UUID parentId, UUID teacherId) {
		admin.update("INSERT INTO parent_teacher_relationships"
			+ " (id, parent_id, teacher_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentId, teacherId, now, now);
	}

	private void insertStudentMetric(int scored, int correct, int totalSec) {
		// 🔴 컬럼 집합은 AnalyticsIntegrationTest 와 같다. 이 테이블에는 created_at·updated_at
		//    이 없다 — 있을 것이라고 짐작하고 쓰면 bad SQL grammar 로 죽는다(실측).
		admin.update("INSERT INTO member_monthly_student_metrics"
			+ " (teacher_id, student_id, month, month_zone, scored_count, correct_count,"
			+ "  total_active_sec, calculation_version, calculated_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			linkedTeacherId, studentProfileId, currentMonth, ZONE,
			scored, correct, totalSec, VERSION, now);
	}

	private UUID insertReport(String status, int revision) {
		UUID id = UUID.randomUUID();
		OffsetDateTime publishedAt = "PUBLISHED".equals(status) ? now : null;
		admin.update("INSERT INTO member_published_reports (id, student_id, teacher_id,"
			+ " report_month, month_zone, revision, status, snapshot_version, created_at,"
			+ " updated_at, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'rs-1', ?, ?, ?)",
			id, studentProfileId, linkedTeacherId, currentMonth, ZONE, revision, status,
			now, now, publishedAt);
		return id;
	}

	private RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	private RequestPostProcessor otherParent() {
		return authentication(principalOf(otherParentAccountId, AccountRole.PARENT));
	}
}
