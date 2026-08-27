package com.checkon.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;
import com.checkon.publication.application.MonthlyReportPublicationRunner;
import com.checkon.publication.domain.PublicationOutcome;
import com.checkon.publication.infrastructure.QueuedDeliveryReader;

/**
 * 🔴 <b>끝에서 끝까지</b> — 승우님 배달 행을 넣고 배치를 돌린 뒤, <b>학부모 컨텍스트로</b>
 * 보고서 API 가 그 보고서를 돌려주는지 본다. 쓰기만 확인하면 반쪽이다.
 *
 * <p>🔴 RLS 가 실제로 걸리는 역할 위에서 돈다({@link MembershipRlsEnforcedSupport}).
 * superuser 로 돌리면 정책이 통째로 우회돼(MB-34) 「강사 컨텍스트가 필요하다」는 사실 자체가
 * 사라져 이 스위트가 무엇을 재는지 흐려진다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MonthlyReportPublicationIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String REPORTS =
		"/api/v1/member/parents/me/children/{studentId}/reports";
	private static final String REPORT = REPORTS + "/{reportId}";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;
	@Autowired MonthlyReportPublicationRunner runner;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private YearMonth month;

	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID studentProfileId;
	private UUID teacherId;
	private UUID reportId;
	private UUID artifactId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		// 🔴 순서가 정해져 있다. V37 원장이 student_profiles·teacher_profiles·parent_profiles 를
		//    RESTRICT 로 참조하므로 member 픽스처 정리보다 **먼저** 지워야 한다.
		clearPublisherFixtures();
		clearFixtures(admin);
		now = OffsetDateTime.now(ZoneOffset.UTC);
		month = YearMonth.from(now);

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);
		UUID studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccountId, "박학생", 2, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", studentAccountId, "박학생", now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, 'STU-AAAAAA', ?)", studentProfileId, now);
		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);

		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentProfileId, studentProfileId, now, now);
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);

		reportId = insertMonthlyReport(READY_PAYLOAD);
		artifactId = insertArtifact(reportId);
	}

	// ══════════════════ 끝에서 끝까지 ══════════════════

	@Test
	@DisplayName("🔴 전제 — 앱 커넥션이 RLS 대상이다 (super=f / bypassrls=f)")
	void applicationRoleIsSubjectToRowLevelSecurity() {
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");
	}

	@Test
	@DisplayName("🔴 전제 — 배치 전에는 학부모 원장이 비어 있다 (admin 으로 확인)")
	void ledgerIsEmptyBeforeTheBatch() {
		assertThat(countPublished()).isZero();
	}

	@Test
	@DisplayName("🔴 끝에서 끝까지 — QUEUED 배달 → 배치 → 학부모 API 가 그 보고서를 돌려준다")
	void queuedDeliveryBecomesParentVisibleReport() throws Exception {
		// 배치 전: 학부모에게 아무것도 안 보인다.
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0));

		insertDelivery(reportId, artifactId, "QUEUED");
		PublicationOutcome outcome = runner.runOnce();
		assertThat(outcome.published()).isEqualTo(1);
		assertThat(outcome.failed()).isZero();

		// 🔴 이게 이 작업의 존재 이유다 — 학부모 컨텍스트로 실제 API 가 돌려주는가.
		MvcResult listed = mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"))
			.andExpect(jsonPath("$.data.items[0].reportMonth").value(month.toString()))
			.andExpect(jsonPath("$.data.items[0].revision").value(1))
			// 🔴 PDF 는 안 만든다 — 저장소가 서로 다르다(MB-62). 계약이 이 상태를 허용한다.
			.andExpect(jsonPath("$.data.items[0].hasPdf").value(false))
			.andReturn();

		String publishedId = com.jayway.jsonpath.JsonPath.read(
			listed.getResponse().getContentAsString(), "$.data.items[0].reportId");
		mockMvc.perform(get(REPORT, studentProfileId, publishedId).with(parent()))
			.andExpect(status().isOk())
			// AI 블록 3종이 그대로 섹션이 됐다. 어휘를 만들지 않았다.
			.andExpect(jsonPath("$.data.sections.length()").value(3))
			.andExpect(jsonPath("$.data.sections[0].kind").value("greeting"))
			.andExpect(jsonPath("$.data.sections[0].status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.sections[1].kind").value("fact"))
			// 🔴 강사가 고친 문장이 학부모에게 간다 — AI 원문이 아니다.
			.andExpect(jsonPath("$.data.sections[1].body").value("강사가 고친 문장"))
			.andExpect(jsonPath("$.data.sections[1].evidenceRefs[0]")
				.value("member_learning_sessions:sess-1"));
	}

	@Test
	@DisplayName("🔴 발행 알림 outbox 가 PENDING 으로 남는다 (PR9 러너가 드레인한다)")
	void publicationLeavesPendingNotificationOutbox() {
		insertDelivery(reportId, artifactId, "QUEUED");
		runner.runOnce();

		assertThat(admin.queryForObject("SELECT status FROM"
			+ " member_report_publication_outbox WHERE teacher_id = ?", String.class, teacherId))
			.isEqualTo("PENDING");
	}

	// ══════════════════ 발행 신호 ══════════════════

	@Test
	@DisplayName("🔴 QUEUED 가 아닌 배달은 발행하지 않는다")
	void nonQueuedDeliveryIsNotPublished() {
		insertDelivery(reportId, artifactId, "DELIVERED");
		assertThat(runner.runOnce().published()).isZero();
		assertThat(countPublished()).isZero();
	}

	@Test
	@DisplayName("🔴 배달이 아예 없으면 발행하지 않는다 — SUCCEEDED 만으로는 신호가 아니다")
	void succeededReportWithoutDeliveryIsNotPublished() {
		// 보고서는 SUCCEEDED 다(setUp 이 그렇게 넣는다). 배달 행만 없다.
		assertThat(runner.runOnce().published()).isZero();
		assertThat(countPublished()).as("검토되지 않은 보고서가 학부모에게 나갔다").isZero();
	}

	@Test
	@DisplayName("🔴 채널이 PARENT_APP 인 배달만 발행한다")
	void onlyParentAppChannelIsPublished() {
		insertDelivery(reportId, artifactId, "QUEUED");
		assertThat(runner.runOnce().published()).isEqualTo(1);
		// 🔴 채널은 CHECK 로 하나뿐이라 다른 값을 넣어 볼 수 없다. 그 사실 자체를 단언한다 —
		//    조건을 빼도 지금은 티가 안 나지만, 채널이 늘어나는 날 조건이 없으면 샌다.
		assertThat(admin.queryForObject("SELECT count(*) FROM pg_constraint"
			+ " WHERE conname = 'ck_monthly_report_delivery_channel'", Integer.class))
			.isEqualTo(1);
	}

	// ══════════════════ 멱등 ══════════════════

	@Test
	@DisplayName("🔴 같은 배달을 두 번 돌려도 행이 안 늘어난다")
	void runningTwiceDoesNotDuplicate() {
		insertDelivery(reportId, artifactId, "QUEUED");
		assertThat(runner.runOnce().published()).isEqualTo(1);

		PublicationOutcome second = runner.runOnce();
		assertThat(second.published()).isZero();
		// 🔴 DELIVERED 표시 덕분에 두 번째 회차는 그 배달을 아예 집지 않는다.
		//    표시 전에는 skip 카운터가 회차마다 자랐다(MB-61 이 그 대가였다).
		assertThat(second.handled()).isZero();
		assertThat(countPublished()).isEqualTo(1);
		assertThat(countSections()).isEqualTo(3);
	}

	@Test
	@DisplayName("🔴 같은 (학생·강사·달) 의 다른 배달도 건너뛴다 — 정정 정책이 미확정(MB-10)")
	void secondDeliveryForSameMonthIsSkipped() {
		insertDelivery(reportId, artifactId, "QUEUED");
		runner.runOnce();

		UUID correctedReport = insertOnDemandReport(READY_PAYLOAD);
		insertDelivery(correctedReport, insertArtifact(correctedReport), "QUEUED");
		PublicationOutcome outcome = runner.runOnce();

		assertThat(outcome.published()).isZero();
		// 🔴 <b>1</b> 이다 — 첫 배달은 이미 DELIVERED 라 안 집힌다. 정정본만 집혀서
		//    「이미 그 달이 있다」로 건너뛴다(MB-10 미확정).
		assertThat(outcome.skipped()).isEqualTo(1);
		assertThat(countPublished()).isEqualTo(1);
		// 🔴 건너뛴 배달도 표시한다 — 안 하면 회차마다 다시 집혀 skip 이 영원히 자란다.
		assertThat(admin.queryForObject("SELECT status FROM monthly_report_deliveries"
			+ " WHERE report_id = ?", String.class, correctedReport)).isEqualTo("DELIVERED");
	}

	// ══════════════════ 내용이 없을 때 ══════════════════

	@Test
	@DisplayName("🔴 섹션이 하나도 없으면 발행하지 않는다 — 빈 보고서를 학부모에게 보내지 않는다")
	void emptyPayloadIsNotPublished() {
		UUID emptyReport = insertOnDemandReport(
			"{\"data\":{\"status\":\"ready\",\"blocks\":[]}}");
		insertDelivery(emptyReport, insertArtifact(emptyReport), "QUEUED");

		PublicationOutcome outcome = runner.runOnce();
		assertThat(outcome.published()).isZero();
		assertThat(outcome.empty()).isEqualTo(1);
		assertThat(countPublished()).isZero();
	}

	@Test
	@DisplayName("🔴 template_only 는 INSUFFICIENT 다 — AVAILABLE 로 올리지 않는다")
	void templateOnlyBecomesInsufficient() throws Exception {
		admin.update("UPDATE monthly_reports SET ai_status = 'template_only' WHERE id = ?",
			reportId);
		insertDelivery(reportId, artifactId, "QUEUED");
		admin.update("UPDATE monthly_reports SET ai_payload = CAST(? AS jsonb) WHERE id = ?",
			READY_PAYLOAD.replace("\"ready\"", "\"template_only\""), reportId);
		runner.runOnce();

		MvcResult listed = mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk()).andReturn();
		String publishedId = com.jayway.jsonpath.JsonPath.read(
			listed.getResponse().getContentAsString(), "$.data.items[0].reportId");
		mockMvc.perform(get(REPORT, studentProfileId, publishedId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sections[0].status").value("INSUFFICIENT"));
	}

	@Test
	@DisplayName("🔴 미산출 항목은 NOT_PRODUCED + 사유로 남는다 (사유는 AI 문자열 그대로)")
	void unproducedSectionsCarryTheirReason() throws Exception {
		UUID withUnproduced = insertOnDemandReport(UNPRODUCED_PAYLOAD);
		insertDelivery(withUnproduced, insertArtifact(withUnproduced), "QUEUED");
		runner.runOnce();

		MvcResult listed = mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk()).andReturn();
		String publishedId = com.jayway.jsonpath.JsonPath.read(
			listed.getResponse().getContentAsString(), "$.data.items[0].reportId");
		mockMvc.perform(get(REPORT, studentProfileId, publishedId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sections[1].kind").value("national_percentile"))
			.andExpect(jsonPath("$.data.sections[1].status").value("NOT_PRODUCED"))
			.andExpect(jsonPath("$.data.sections[1].unproducedReason")
				.value("BE 비교집단 API·원천·모수·산식 계약이 확정되지 않음"));
	}

	// ══════════════════ 상한 ══════════════════

	/**
	 * 🔴 승우님 원장에 쓰는 것은 <b>이 표시 하나뿐</b>이다(MB-61). 주인이 없음을 실측으로
	 * 확인하고 넣었다 — 승우님 코드는 {@code QUEUED} 만 만들고 {@code DELIVERED} 를 쓰는
	 * 코드가 밖에 0곳이며, 유일한 소비처가 둘을 똑같이 취급한다.
	 */
	@Test
	@DisplayName("🔴 발행 성공한 배달만 DELIVERED 로 표시한다 (delivered_at 함께)")
	void successfulDeliveryIsMarkedDelivered() {
		insertDelivery(reportId, artifactId, "QUEUED");
		runner.runOnce();

		assertThat(admin.queryForObject("SELECT status FROM monthly_report_deliveries"
			+ " WHERE report_id = ?", String.class, reportId)).isEqualTo("DELIVERED");
		// 🔴 CHECK 가 DELIVERED ⟹ delivered_at NOT NULL AND failure_code NULL 을 요구한다.
		assertThat(admin.queryForObject("SELECT count(*) FROM monthly_report_deliveries"
			+ " WHERE report_id = ? AND delivered_at IS NOT NULL AND failure_code IS NULL",
			Integer.class, reportId)).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 실패한 배달은 QUEUED 로 남는다 — 재시도 가능해야 한다")
	void failedDeliveryStaysQueued() {
		// 🔴 payload 가 비어 「발행할 내용 없음」이 되는 경우도 표시하지 않는다 —
		//    AI 가 나중에 채울 수 있으므로 대기 상태를 유지한다.
		UUID emptyReport = insertOnDemandReport(
			"{\"data\":{\"status\":\"ready\",\"blocks\":[]}}");
		insertDelivery(emptyReport, insertArtifact(emptyReport), "QUEUED");

		assertThat(runner.runOnce().empty()).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT status FROM monthly_report_deliveries"
			+ " WHERE report_id = ?", String.class, emptyReport)).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("🔴 표시 덕분에 두 번째 회차는 그 배달을 다시 집지 않는다")
	void markedDeliveryIsNotPickedAgain() {
		insertDelivery(reportId, artifactId, "QUEUED");
		assertThat(runner.runOnce().published()).isEqualTo(1);

		PublicationOutcome second = runner.runOnce();
		assertThat(second.handled()).as("표시했는데 다시 집었다").isZero();
	}

	/**
	 * 🔴 <b>{@code SKIP LOCKED} 가 실제로 건너뛰는지</b> 결정적으로 잰다 — 스레드 경쟁이 아니라
	 * <b>커넥션 둘</b>로 본다. 경쟁 테스트는 타이밍에 기대 flaky 가 되고, 그런 테스트는
	 * 「가끔 초록」이라 아무것도 증명하지 못한다.
	 *
	 * <p>커넥션 A 가 배달을 잠근 채 트랜잭션을 유지하는 동안, 커넥션 B 가 같은 질의를 한다:</p>
	 * <ul>
	 *   <li>{@code SKIP LOCKED} 가 있으면 → <b>즉시 빈 결과</b>. 다른 인스턴스가 잡은 것을
	 *       건너뛰고 제 갈 길을 간다</li>
	 *   <li>없으면 → <b>블록</b>된다. 그래서 B 에 짧은 {@code lock_timeout} 을 걸어
	 *       매달리는 대신 예외로 드러나게 한다 — 테스트가 <b>멈추지 않고 실패</b>해야 한다</li>
	 * </ul>
	 *
	 * <p>🔴 두 커넥션 다 관리자 자격이라 RLS 는 우회된다. 여기서 재는 것은 <b>잠금 동작</b>이고
	 * 격리는 다른 테스트들이 제한 역할로 잰다 — 한 테스트가 두 가지를 재면 무엇이 깨졌는지
	 * 흐려진다.</p>
	 */
	@Test
	@DisplayName("🔴 SKIP LOCKED — 한 인스턴스가 잠근 배달을 다른 인스턴스가 건너뛴다")
	void skipLockedLetsAnotherRunnerMoveOn() {
		insertDelivery(reportId, artifactId, "QUEUED");

		JdbcTemplate first = new JdbcTemplate(newAdminDataSource());
		JdbcTemplate second = new JdbcTemplate(newAdminDataSource());
		QueuedDeliveryReader readerA = new QueuedDeliveryReader(first);
		QueuedDeliveryReader readerB = new QueuedDeliveryReader(second);
		TransactionTemplate txA = new TransactionTemplate(
			new JdbcTransactionManager(first.getDataSource()));
		TransactionTemplate txB = new TransactionTemplate(
			new JdbcTransactionManager(second.getDataSource()));

		Boolean secondSawNothing = txA.execute(outer -> {
			assertThat(readerA.lockNextQueued(Set.of()))
				.as("A 가 배달을 못 집었다 — 이 테스트의 전제가 깨졌다")
				.isPresent();
			return txB.execute(inner -> {
				// 🔴 SKIP LOCKED 가 없으면 여기서 블록된다. 매달리지 않고 터지게 한다.
				second.execute("SET LOCAL lock_timeout = '500ms'");
				return readerB.lockNextQueued(Set.of()).isEmpty();
			});
		});

		assertThat(secondSawNothing)
			.as("B 가 A 가 잠근 배달을 함께 집었다 — 두 인스턴스가 같은 배달을 발행한다")
			.isTrue();
	}

	private javax.sql.DataSource newAdminDataSource() {
		org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
			new org.springframework.jdbc.datasource.DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource;
	}

	// ──────────────────────────── 픽스처 ────────────────────────────

	/** 🔴 AI 결과의 실측 구조. {@code MonthlyReportResultListener} 가 저장하는 모양 그대로다. */
	private static final String READY_PAYLOAD = """
		{"data":{"status":"ready","blocks":[
		 {"block_id":"11111111-1111-1111-1111-111111111111","seq":0,"block_type":"greeting",
		  "active_revision_no":0,
		  "revisions":[{"revision_no":0,"revision_kind":"ai_draft","ai_original":"안녕하세요",
		    "teacher_edit":null,"evidence":[{"source_table":"member_attempts",
		    "record_id":"att-1","summary":"요약"}]}]},
		 {"block_id":"22222222-2222-2222-2222-222222222222","seq":1,"block_type":"fact",
		  "active_revision_no":1,
		  "revisions":[{"revision_no":0,"revision_kind":"ai_draft","ai_original":"AI 원문",
		    "teacher_edit":null,"evidence":[{"source_table":"member_learning_sessions",
		    "record_id":"sess-1","summary":"요약"}]},
		   {"revision_no":1,"revision_kind":"teacher_edit","ai_original":"AI 원문",
		    "teacher_edit":"강사가 고친 문장","evidence":[{"source_table":"member_learning_sessions",
		    "record_id":"sess-1","summary":"요약"}]}]},
		 {"block_id":"33333333-3333-3333-3333-333333333333","seq":2,"block_type":"closing",
		  "active_revision_no":0,
		  "revisions":[{"revision_no":0,"revision_kind":"ai_draft","ai_original":"맺음말",
		    "teacher_edit":null,"evidence":[{"source_table":"member_attempts",
		    "record_id":"att-2","summary":"요약"}]}]}]}}
		""";

	private static final String UNPRODUCED_PAYLOAD = """
		{"data":{"status":"ready","blocks":[
		 {"block_id":"44444444-4444-4444-4444-444444444444","seq":0,"block_type":"greeting",
		  "active_revision_no":0,
		  "revisions":[{"revision_no":0,"revision_kind":"ai_draft","ai_original":"안녕하세요",
		    "teacher_edit":null,"evidence":[{"source_table":"member_attempts",
		    "record_id":"att-1","summary":"요약"}]}]}],
		 "unproduced_sections":[{"key":"national_percentile","status":"NOT_PRODUCED",
		   "reason":"BE 비교집단 API·원천·모수·산식 계약이 확정되지 않음"}]}}
		""";

	private UUID insertMonthlyReport(String aiPayload) {
		return insertReport(aiPayload, "MONTHLY");
	}

	/**
	 * 🔴 <b>같은 달에 두 번째 {@code MONTHLY} 보고서는 만들 수 없다.</b> 승우님이
	 * {@code uq_monthly_reports_canonical_month}(teacher_id, student_id, report_month)
	 * {@code WHERE report_kind='MONTHLY'} 를 걸어 뒀다 — 재발행은 {@code ON_DEMAND} 다.
	 * 🔴 이 제약은 우리 멱등 키 선택을 뒷받침한다: 원본 자체가 「달마다 하나」를 전제한다.
	 */
	private UUID insertOnDemandReport(String aiPayload) {
		return insertReport(aiPayload, "ON_DEMAND");
	}

	private UUID insertReport(String aiPayload, String kind) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO monthly_reports (id, teacher_id, student_id, guardian_ref,"
			+ " report_month, report_kind, client_idempotency_key, request_hash, source_payload,"
			+ " request_status, ai_status, ai_payload, block_count, created_at, updated_at,"
			+ " completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb,"
			+ " 'SUCCEEDED', 'ready', CAST(? AS jsonb), 3, ?, ?, ?)",
			id, teacherId, studentProfileId, "pa_" + hex(), month.atDay(1), kind,
			"key-" + UUID.randomUUID(), "sha256:" + hex() + hex(), aiPayload, now, now, now);
		return id;
	}

	private UUID insertArtifact(UUID report) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO monthly_report_artifacts (id, teacher_id, report_id,"
			+ " revision_no, storage_key, sha256, page_count, status, created_at)"
			+ " VALUES (?, ?, ?, 1, ?, ?, 2, 'READY', ?)",
			id, teacherId, report, "teacher-storage/" + id + ".pdf",
			"sha256:" + hex() + hex(), now);
		return id;
	}

	private void insertDelivery(UUID report, UUID artifact, String status) {
		OffsetDateTime deliveredAt = "DELIVERED".equals(status) ? now : null;
		admin.update("INSERT INTO monthly_report_deliveries (id, teacher_id, report_id,"
			+ " artifact_id, parent_id, client_idempotency_key, request_hash, status, channel,"
			+ " failure_code, queued_at, delivered_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PARENT_APP', NULL, ?, ?)",
			UUID.randomUUID(), teacherId, report, artifact, parentProfileId,
			"dk-" + UUID.randomUUID(), "sha256:" + hex() + hex(), status, now, deliveredAt);
	}

	/**
	 * 🔴 승우님 원장 정리. {@code clearMemberFixtures} 는 V37 테이블을 모르므로 여기서
	 * 자식 → 부모 순서로 지운다 — 그 테이블들이 {@code teacher_profiles} 를 참조한다.
	 */
	private void clearPublisherFixtures() {
		for (String table : List.of("monthly_report_deliveries", "monthly_report_artifacts",
			"monthly_report_outbox", "monthly_report_result_inbox", "monthly_reports")) {
			admin.update("DELETE FROM " + table);
		}
	}

	private int countPublished() {
		Integer count = admin.queryForObject(
			"SELECT count(*) FROM member_published_reports", Integer.class);
		return count == null ? 0 : count;
	}

	private int countSections() {
		Integer count = admin.queryForObject(
			"SELECT count(*) FROM member_published_report_sections", Integer.class);
		return count == null ? 0 : count;
	}

	private static String hex() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}
}
