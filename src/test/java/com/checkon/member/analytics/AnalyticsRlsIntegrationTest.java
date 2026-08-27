package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.support.MemberPostgresSupport;

/**
 * V42 3테이블 정책이 <b>제한 역할 위에서 실제로</b> 강제되는지 증명한다 —
 * {@code member_monthly_student_metrics} · {@code member_monthly_weakness_metrics}
 * · {@code member_metric_refresh_outbox}.
 *
 * <p>🔴 기존 {@link com.checkon.member.auth.MemberRlsContextIntegrationTest} 와 같은 형태다.
 * 새 형태를 발명하지 않는다 — 픽스처는 superuser 로 넣고 <b>검증만</b> 제한 역할로 한다.
 * 픽스처까지 제한 역할로 넣으면 INSERT 정책에 걸려 이 스위트가 무엇을 재는지 흐려진다.</p>
 *
 * <p>🔴 <b>전제 단언 2개</b> — ① 이 커넥션이 정말 RLS 대상인가({@code rolsuper/rolbypassrls =
 * false/false}), ② 그 행이 admin 커넥션으로는 정말 보이는가. ②가 없으면 아래 {@code isEmpty()}
 * 단언이 「데이터가 원래 없어서」 통과했는지 「정책이 가려서」 통과했는지 구분할 수 없다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class AnalyticsRlsIntegrationTest extends MemberPostgresSupport {

	private static final String RESTRICTED_ROLE = "member_rls_probe";
	private static final String RESTRICTED_PASSWORD = "member_rls_probe_pw";

	private static final String MONTH = "2026-08";
	private static final String ZONE = "Asia/Seoul";
	private static final String VERSION = "mm-1";
	private static final String AREA = "reading";
	private static final String TYPE = "fact";

	@Autowired JdbcTemplate jdbcTemplate;

	private JdbcTemplate restricted;
	private TransactionTemplate restrictedTx;

	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID otherStudentAccountId;
	private UUID otherStudentProfileId;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID teacherId;

	@BeforeEach
	void setUp() {
		createRestrictedRole();
		restricted = new JdbcTemplate(restrictedDataSource());
		restrictedTx = new TransactionTemplate(
			new JdbcTransactionManager(restricted.getDataSource()));

		clearMemberFixtures(jdbcTemplate);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

		studentAccountId = insertAccount("student@example.com", "STUDENT", now);
		studentProfileId = MemberPostgresSupport.insertStudentProfile(
			jdbcTemplate, studentAccountId, "박학생", 2, now);

		otherStudentAccountId = insertAccount("other-student@example.com", "STUDENT", now);
		otherStudentProfileId = MemberPostgresSupport.insertStudentProfile(
			jdbcTemplate, otherStudentAccountId, "이학생", 2, now);

		parentAccountId = insertAccount("parent@example.com", "PARENT", now);
		parentProfileId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?)", parentProfileId, parentAccountId, now, now);

		UUID teacherAccountId = insertAccount("teacher@example.com", "TEACHER", now);
		teacherId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO teacher_profiles (id, account_id, display_name,"
			+ " created_at, updated_at) VALUES (?, ?, '김강사', ?, ?)",
			teacherId, teacherAccountId, now, now);

		// 학부모↔자녀 활성 관계 — 학부모가 scope 를 열려면 이 행이 이미 있어야 한다.
		jdbcTemplate.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentProfileId, studentProfileId, now, now);
		// 강사 관계는 metrics FK 만족용. 이 스위트에서 강사 컨텍스트를 열지 않는다(절대 규칙 3).
		jdbcTemplate.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);
		jdbcTemplate.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, otherStudentProfileId, now, now);

		insertStudentMetric(studentProfileId, 20, 12, 600);
		insertStudentMetric(otherStudentProfileId, 15, 9, 400);
		insertWeaknessMetric(studentProfileId, 20, 12);
		insertWeaknessMetric(otherStudentProfileId, 15, 9);
		insertOutboxPending(studentProfileId);
		insertOutboxPending(otherStudentProfileId);
	}

	// ────────── 전제 단언 ──────────

	@Test
	@DisplayName("🔴 전제 ① — 제한 역할이 정말 RLS 대상이다 (false/false)")
	void restrictedRoleIsSubjectToRls() {
		String flags = restricted.queryForObject(
			"SELECT rolsuper||'/'||rolbypassrls FROM pg_roles WHERE rolname = current_user",
			String.class);
		assertThat(flags)
			.as("superuser 나 BYPASSRLS 면 정책이 통째로 우회돼 아래 단언이 무의미해진다")
			.isEqualTo("false/false");
	}

	@Test
	@DisplayName("🔴 전제 ② — 픽스처가 admin 커넥션으로는 정말 보인다 (아래 isEmpty 가 뭘 재는지 정한다)")
	void adminSeesTheFixtures() {
		Integer sm = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_monthly_student_metrics", Integer.class);
		Integer wm = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_monthly_weakness_metrics", Integer.class);
		Integer ob = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_metric_refresh_outbox", Integer.class);
		assertThat(sm).isEqualTo(2);
		assertThat(wm).isEqualTo(2);
		assertThat(ob).isEqualTo(2);
	}

	// ────────── 컨텍스트 없음 ──────────

	@Test
	@DisplayName("🔴 컨텍스트를 아무것도 안 열면 세 테이블 모두 0행 (예외가 아니다 · §6-4-3)")
	void noContextMeansZeroRows() {
		List<String> student = restrictedTx.execute(status ->
			restricted.queryForList("SELECT id::text FROM member_monthly_student_metrics",
				String.class));
		List<String> weakness = restrictedTx.execute(status ->
			restricted.queryForList("SELECT id::text FROM member_monthly_weakness_metrics",
				String.class));
		List<String> outbox = restrictedTx.execute(status ->
			restricted.queryForList("SELECT id::text FROM member_metric_refresh_outbox",
				String.class));

		assertThat(student).as("조용히 0행이라 200 + 빈 지표로 위장된다").isEmpty();
		assertThat(weakness).isEmpty();
		assertThat(outbox).isEmpty();
	}

	// ────────── 학생 self ──────────

	@Test
	@DisplayName("🔴 학생 self — 자기 행만 보인다 (다른 학생 행 섞이면 정책 어긋난 것)")
	void studentSelfSeesOnlyOwnRows() {
		List<UUID> studentRows = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			setConfig("checkon.current_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_student_metrics",
				UUID.class);
		});
		List<UUID> weaknessRows = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			setConfig("checkon.current_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_weakness_metrics",
				UUID.class);
		});
		List<UUID> outboxRows = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			setConfig("checkon.current_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_metric_refresh_outbox",
				UUID.class);
		});

		assertThat(studentRows).containsExactly(studentProfileId);
		assertThat(weaknessRows).containsExactly(studentProfileId);
		assertThat(outboxRows).containsExactly(studentProfileId);
	}

	@Test
	@DisplayName("🔴 남의 student_id 를 넣으면 0행 (정책이 술어에서 걸러내는지)")
	void foreignStudentIdSeesZero() {
		List<UUID> studentRows = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			setConfig("checkon.current_student_id", otherStudentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_student_metrics",
				UUID.class);
		});
		List<UUID> weaknessRows = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			setConfig("checkon.current_student_id", otherStudentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_weakness_metrics",
				UUID.class);
		});

		// 정책이 술어에서 student_id = current_checkon_student_id() 를 요구하므로,
		// 실제로 남의 행이 그 값이 아닌 이상 결과는 0행이어야 한다.
		assertThat(studentRows).doesNotContain(otherStudentProfileId).isEmpty();
		assertThat(weaknessRows).doesNotContain(otherStudentProfileId).isEmpty();
	}

	// ────────── 학부모 scope ──────────

	@Test
	@DisplayName("🔴 학부모는 자녀 scope 를 열어야 자녀 지표를 본다 · 안 열면 0행 (§6-4-2)")
	void parentNeedsChildScope() {
		List<UUID> withoutScope = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			setConfig("checkon.current_parent_id", parentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_student_metrics",
				UUID.class);
		});
		assertThat(withoutScope).as("주체만으로는 못 본다 — scope 가 있어야 한다").isEmpty();

		List<UUID> withScope = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_student_metrics",
				UUID.class);
		});
		List<UUID> withScopeWeakness = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_weakness_metrics",
				UUID.class);
		});

		assertThat(withScope).containsExactly(studentProfileId);
		assertThat(withScopeWeakness).containsExactly(studentProfileId);
	}

	@Test
	@DisplayName("🔴 학부모가 자기 자녀 scope 를 열어도 남의 자녀 행은 새어나가지 않는다")
	void parentScopeToOwnChildDoesNotLeakStranger() {
		// 🔴 「남의 자녀 id 를 scope 에 넣으면 0행」이 아니다 — V42:159-165 술어는
		//    student_id = current_checkon_scope_student_id() 값 매칭만 한다. 관계 검증은
		//    서비스 계층(withVerifiedChildScope)이 scope 를 열기 전에 한다. 이 테스트는 기존
		//    MemberRlsContextIntegrationTest.parentScopeDoesNotOpenOtherChildren 과 같은
		//    형태다 — 자기 자녀 scope 를 열고 남의 자녀가 결과에 섞이지 않음을 본다.
		List<UUID> visibleStudent = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_student_metrics", UUID.class);
		});
		List<UUID> visibleWeakness = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_monthly_weakness_metrics", UUID.class);
		});

		// 자기 자녀 하나만. 남의 자녀가 섞이면 술어가 컨텍스트 상수와 값을 잘못 비교한 것.
		assertThat(visibleStudent).containsExactly(studentProfileId);
		assertThat(visibleWeakness).containsExactly(studentProfileId);
	}

	// ────────── outbox — 학부모·강사 정책 없음 ──────────

	@Test
	@DisplayName("🔴 outbox 는 학부모·강사 정책이 없다 → 학부모가 scope 를 열어도 0행")
	void outboxHasNoParentPolicy() {
		List<UUID> parentSeesOutbox = restrictedTx.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id FROM member_metric_refresh_outbox", UUID.class);
		});
		assertThat(parentSeesOutbox)
			.as("outbox 는 학생 self 정책만 있다 (V42:210-231)")
			.isEmpty();
	}

	// ────────── 헬퍼 ──────────

	private void setConfig(String name, UUID value) {
		restricted.queryForObject("SELECT set_config(?, ?, true)", String.class,
			name, value.toString());
	}

	private UUID insertAccount(String email, String role, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO accounts (id, email, role, status, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?)", id, email, role, now);
		return id;
	}

	private void insertStudentMetric(UUID studentId, int scored, int correct, int totalSec) {
		jdbcTemplate.update("INSERT INTO member_monthly_student_metrics"
			+ " (teacher_id, student_id, month, month_zone, scored_count, correct_count,"
			+ "  total_active_sec, calculation_version, calculated_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			teacherId, studentId, MONTH, ZONE, scored, correct, totalSec, VERSION,
			OffsetDateTime.now(ZoneOffset.UTC));
	}

	private void insertWeaknessMetric(UUID studentId, int scored, int correct) {
		jdbcTemplate.update("INSERT INTO member_monthly_weakness_metrics"
			+ " (teacher_id, student_id, month, month_zone, area_tag, type_tag,"
			+ "  scored_count, correct_count, status, calculation_version, calculated_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?)",
			teacherId, studentId, MONTH, ZONE, AREA, TYPE,
			scored, correct, VERSION, OffsetDateTime.now(ZoneOffset.UTC));
	}

	private void insertOutboxPending(UUID studentId) {
		jdbcTemplate.update("INSERT INTO member_metric_refresh_outbox"
			+ " (teacher_id, student_id, month, month_zone, reason, status,"
			+ "  attempt_count, created_at)"
			+ " VALUES (?, ?, ?, ?, 'ATTEMPT_SCORED', 'PENDING', 0, ?)",
			teacherId, studentId, MONTH, ZONE, OffsetDateTime.now(ZoneOffset.UTC));
	}

	private void createRestrictedRole() {
		Integer exists = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, RESTRICTED_ROLE);
		if (exists == null || exists == 0) {
			jdbcTemplate.execute("CREATE ROLE " + RESTRICTED_ROLE
				+ " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS"
				+ " PASSWORD '" + RESTRICTED_PASSWORD + "'");
		}
		jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RESTRICTED_ROLE);
		jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public"
			+ " TO " + RESTRICTED_ROLE);
		jdbcTemplate.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RESTRICTED_ROLE);
	}

	private DriverManagerDataSource restrictedDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(RESTRICTED_ROLE);
		dataSource.setPassword(RESTRICTED_PASSWORD);
		return dataSource;
	}
}
