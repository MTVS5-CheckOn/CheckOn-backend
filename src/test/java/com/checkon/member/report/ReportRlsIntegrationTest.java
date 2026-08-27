package com.checkon.member.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.support.MemberPostgresSupport;

/**
 * V45 네 테이블의 정책·제약이 <b>제한 역할 위에서 실제로</b> 강제되는지 증명한다.
 *
 * <p>🔴 {@link com.checkon.member.analytics.AnalyticsRlsIntegrationTest} 와 같은 형태다.
 * 새 형태를 발명하지 않는다 — 픽스처는 superuser 로 넣고 <b>검증만</b> 제한 역할로 한다.</p>
 *
 * <p>🔴 <b>전제 단언 2개</b> — ① 이 커넥션이 정말 RLS 대상인가({@code rolsuper/rolbypassrls =
 * false/false}), ② 그 행이 admin 커넥션으로는 정말 보이는가. ②가 없으면 아래
 * {@code isEmpty()} 단언이 「데이터가 원래 없어서」 통과했는지 「정책이 가려서」 통과했는지
 * 구분할 수 없다(MB-34).</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class ReportRlsIntegrationTest extends MemberPostgresSupport {

	private static final String RESTRICTED_ROLE = "member_rls_probe";
	private static final String RESTRICTED_PASSWORD = "member_rls_probe_pw";

	private static final String MONTH = "2026-08";
	private static final String ZONE = "Asia/Seoul";
	private static final String VERSION = "rs-1";
	private static final String CHECKSUM =
		"sha256:" + "a".repeat(64);

	@Autowired JdbcTemplate jdbcTemplate;

	private JdbcTemplate restricted;
	private TransactionTemplate restrictedTx;

	private UUID studentProfileId;
	private UUID otherStudentProfileId;
	private UUID parentProfileId;
	private UUID otherParentProfileId;
	private UUID teacherId;

	private OffsetDateTime reportPublishedAt;
	private UUID publishedReportId;
	private UUID draftReportId;
	private UUID reviewReadyReportId;
	private UUID failedReportId;
	private UUID otherStudentReportId;

	@BeforeEach
	void setUp() {
		restricted = new JdbcTemplate(restrictedDataSource());
		restrictedTx = new TransactionTemplate(
			new JdbcTransactionManager(restricted.getDataSource()));

		clearMemberFixtures(jdbcTemplate);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		// 🔴 복합 FK (report_id, published_at) 가 정확히 같은 값을 요구한다. 자식 픽스처가
		//    자기 now 를 새로 뜨면 FK 위반이다 — 그게 이 설계가 노리는 바다.
		reportPublishedAt = now;

		UUID studentAccountId = insertAccount("student@example.com", "STUDENT", now);
		studentProfileId = MemberPostgresSupport.insertStudentProfile(
			jdbcTemplate, studentAccountId, "박학생", 2, now);
		UUID otherStudentAccountId = insertAccount("other-student@example.com", "STUDENT", now);
		otherStudentProfileId = MemberPostgresSupport.insertStudentProfile(
			jdbcTemplate, otherStudentAccountId, "이학생", 2, now);

		parentProfileId = insertParentProfile("parent@example.com", now);
		otherParentProfileId = insertParentProfile("other-parent@example.com", now);

		UUID teacherAccountId = insertAccount("teacher@example.com", "TEACHER", now);
		teacherId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO teacher_profiles (id, account_id, display_name,"
			+ " created_at, updated_at) VALUES (?, ?, '김강사', ?, ?)",
			teacherId, teacherAccountId, now, now);

		link(parentProfileId, studentProfileId, now);
		link(otherParentProfileId, otherStudentProfileId, now);
		teacherLink(teacherId, studentProfileId, now);
		teacherLink(teacherId, otherStudentProfileId, now);

		publishedReportId = insertReport(studentProfileId, 1, "PUBLISHED", now);
		draftReportId = insertReport(studentProfileId, 2, "DRAFT", now);
		reviewReadyReportId = insertReport(studentProfileId, 3, "REVIEW_READY", now);
		failedReportId = insertReport(studentProfileId, 4, "FAILED", now);
		otherStudentReportId = insertReport(otherStudentProfileId, 1, "PUBLISHED", now);

		insertSection(publishedReportId, studentProfileId, reportPublishedAt,
			"overall", "AVAILABLE", null);
		insertSection(draftReportId, studentProfileId, null, "overall", "AVAILABLE", null);
		insertFile(publishedReportId, studentProfileId, reportPublishedAt);
		insertOutbox(publishedReportId, studentProfileId, reportPublishedAt);
	}

	// ────────── 전제 단언 ──────────

	@Test
	@DisplayName("🔴 전제 ① 검증 커넥션은 RLS 대상이다 (super=f / bypassrls=f)")
	void probeRoleIsSubjectToRowLevelSecurity() {
		assertThat(privilegeFlags(restricted)).isEqualTo("false/false");
	}

	@Test
	@DisplayName("🔴 전제 ② 미발행 행은 admin 커넥션으로는 실제로 보인다")
	void unpublishedRowsExistForAdmin() {
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_published_reports WHERE student_id = ?"
				+ " AND status <> 'PUBLISHED'", Integer.class, studentProfileId))
			.isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_published_report_sections WHERE report_id = ?",
			Integer.class, draftReportId)).isEqualTo(1);
	}

	// ────────── 미발행 은닉 (SQL 계층 단독) ──────────

	@Test
	@DisplayName("🔴 DRAFT·REVIEW_READY·FAILED 는 학부모 컨텍스트에서 SQL 계층부터 0건이다")
	void unpublishedIsInvisibleAtSqlLayer() {
		for (UUID hidden : List.of(draftReportId, reviewReadyReportId, failedReportId)) {
			assertThat(asParent(parentProfileId, studentProfileId, () ->
				restricted.queryForList(
					"SELECT id FROM member_published_reports WHERE id = ?", hidden)))
				.as("미발행 보고서 %s 가 학부모에게 보인다", hidden)
				.isEmpty();
		}
		assertThat(asParent(parentProfileId, studentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_published_reports WHERE student_id = ?",
				studentProfileId)))
			.hasSize(1);
	}

	@Test
	@DisplayName("🔴 미발행 보고서의 섹션도 SQL 계층부터 0건이다")
	void unpublishedSectionsAreInvisibleAtSqlLayer() {
		assertThat(asParent(parentProfileId, studentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_published_report_sections WHERE report_id = ?",
				draftReportId)))
			.isEmpty();
		assertThat(asParent(parentProfileId, studentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_published_report_sections WHERE report_id = ?",
				publishedReportId)))
			.hasSize(1);
	}

	@Test
	@DisplayName("🔴 범위를 안 열면 발행본도 0건이다 — 계정만 열어서는 안 보인다")
	void scopeIsRequiredEvenForPublishedRows() {
		List<java.util.Map<String, Object>> rows = restrictedTx.execute(status -> {
			setConfig("checkon.current_parent_id", parentProfileId.toString());
			return restricted.queryForList(
				"SELECT id FROM member_published_reports WHERE student_id = ?",
				studentProfileId);
		});
		assertThat(rows).isEmpty();
	}

	/**
	 * 🔴 <b>이 단언이 재는 것을 정확히 적는다.</b> 학부모 B 가 자기 범위(자기 자녀)를 연 채로는
	 * 남의 자녀 발행본을 못 본다 — 그것이 정책이 보장하는 전부다.
	 *
	 * <p>🔴 <b>범위 세션 변수를 남의 자녀로 <em>직접</em> 세팅하는 경우는 정책이 못 막는다.</b>
	 * 설계 §6-4-2 가 「{@code current_checkon_parent_id} 와 같은 수준의 신뢰 경계」라고 이미
	 * 규정했다 — 범위를 올바로 채우는 것은 애플리케이션 책임이고,
	 * {@code MemberDatabaseContext.withVerifiedChildScope} 가 확인을 삼켜 건너뛸 문법적 방법을
	 * 없앤다. 그 층의 증명은 {@code ReportIntegrationTest#otherParentCannotRead} 가 한다.
	 * 여기서 그것까지 재는 척하면 <b>덮은 척하는 테스트</b>가 된다.</p>
	 */
	@Test
	@DisplayName("🔴 학부모 B 는 자기 범위에서 남의 자녀 발행본을 못 본다")
	void otherParentCannotReadWithinOwnScope() {
		assertThat(asParent(otherParentProfileId, otherStudentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_published_reports WHERE id = ?", publishedReportId)))
			.isEmpty();
		assertThat(asParent(otherParentProfileId, otherStudentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_published_reports WHERE id = ?", otherStudentReportId)))
			.hasSize(1);
	}

	@Test
	@DisplayName("🔴 파일 행도 미발행이면 안 보이고, 남의 자녀 것도 안 보인다")
	void fileRowsFollowTheSameIsolation() {
		assertThat(asParent(parentProfileId, studentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_report_files WHERE report_id = ?", publishedReportId)))
			.hasSize(1);
		assertThat(asParent(otherParentProfileId, otherStudentProfileId, () ->
			restricted.queryForList(
				"SELECT id FROM member_report_files WHERE report_id = ?", publishedReportId)))
			.isEmpty();
	}

	// ────────── PUBLISHED 불변성 ──────────

	@Test
	@DisplayName("🔴 강사 컨텍스트로도 PUBLISHED 행은 UPDATE 0행이다 (USING 이 막는다)")
	void publishedRowCannotBeUpdated() {
		int published = asTeacher(() -> restricted.update(
			"UPDATE member_published_reports SET snapshot_version = 'hacked' WHERE id = ?",
			publishedReportId));
		assertThat(published).as("발행본이 수정됐다").isZero();

		int draft = asTeacher(() -> restricted.update(
			"UPDATE member_published_reports SET snapshot_version = 'edited' WHERE id = ?",
			draftReportId));
		assertThat(draft).as("초안은 고칠 수 있어야 한다 — USING 이 과하게 막았다").isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 발행된 섹션·파일 행도 UPDATE 0행이다")
	void publishedChildRowsCannotBeUpdated() {
		assertThat(asTeacher(() -> restricted.update(
			"UPDATE member_published_report_sections SET body = 'hacked' WHERE report_id = ?",
			publishedReportId))).isZero();
		assertThat(asTeacher(() -> restricted.update(
			"UPDATE member_report_files SET object_key = 'hacked' WHERE report_id = ?",
			publishedReportId))).isZero();
	}

	@Test
	@DisplayName("정정은 새 revision 이다 — 같은 (학생, 강사, 달, revision) 은 한 번뿐")
	void revisionAllowsCorrection() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		UUID second = insertReport(studentProfileId, 5, "PUBLISHED", now);
		assertThat(second).isNotNull();

		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO member_published_reports (id, student_id, teacher_id, report_month,"
				+ " month_zone, revision, status, snapshot_version, created_at, updated_at,"
				+ " published_at) VALUES (?, ?, ?, ?, ?, 5, 'PUBLISHED', ?, ?, ?, ?)",
			UUID.randomUUID(), studentProfileId, teacherId, MONTH, ZONE, VERSION,
			now, now, now))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ────────── 스키마의 정직성 장치 ──────────

	@Test
	@DisplayName("🔴 NOT_PRODUCED 인데 사유가 없으면 INSERT 자체가 실패한다")
	void notProducedWithoutReasonIsRejected() {
		assertThatThrownBy(() -> insertSection(publishedReportId, studentProfileId,
			reportPublishedAt, "percentile", "NOT_PRODUCED", null))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(insertSection(publishedReportId, studentProfileId, reportPublishedAt,
			"weakness", "NOT_PRODUCED", "BE 비교집단 API·원천·모수·산식 계약이 확정되지 않음"))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 AVAILABLE 인데 body·content 가 둘 다 비면 INSERT 가 실패한다")
	void availableWithoutContentIsRejected() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO member_published_report_sections (id, report_id, student_id,"
				+ " teacher_id, published_at, kind, ordinal, status, evidence_refs, created_at)"
				+ " VALUES (?, ?, ?, ?, ?, 'empty', 9, 'AVAILABLE', '[]'::jsonb, ?)",
			UUID.randomUUID(), publishedReportId, studentProfileId, teacherId,
			reportPublishedAt, now))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("🔴 자식 행의 published_at 이 부모와 갈리면 복합 FK 가 막는다")
	void childCannotDivergeFromParentPublishedAt() {
		OffsetDateTime wrong = reportPublishedAt.plusDays(1);
		assertThatThrownBy(() -> insertSection(
			publishedReportId, studentProfileId, wrong, "diverged", "NO_DATA", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("🔴 미발행 보고서의 outbox 행은 물리적으로 못 들어온다")
	void outboxCannotReferenceUnpublishedReport() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO member_report_publication_outbox (id, report_id, student_id,"
				+ " teacher_id, published_at, status, created_at)"
				+ " VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
			UUID.randomUUID(), draftReportId, studentProfileId, teacherId, reportPublishedAt, now))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("🔴 status 와 published_at 이 갈리면 INSERT 가 실패한다")
	void statusAndPublishedAtCannotDiverge() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO member_published_reports (id, student_id, teacher_id, report_month,"
				+ " month_zone, revision, status, snapshot_version, created_at, updated_at,"
				+ " published_at) VALUES (?, ?, ?, ?, ?, 9, 'DRAFT', ?, ?, ?, ?)",
			UUID.randomUUID(), studentProfileId, teacherId, MONTH, ZONE, VERSION, now, now, now))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ──────────────────────────── 도우미 ────────────────────────────

	private <T> T asParent(UUID parentId, UUID scopeStudentId, java.util.function.Supplier<T> body) {
		return restrictedTx.execute(status -> {
			setConfig("checkon.current_parent_id", parentId.toString());
			setConfig("checkon.scope_student_id", scopeStudentId.toString());
			return body.get();
		});
	}

	/**
	 * 🔴 <b>강사 컨텍스트는 이 스위트에서만 연다.</b> 프로덕션 member 코드는 절대 규칙 3 으로
	 * {@code checkon.current_teacher_id} 를 설정하지 않는다 — 여기서는 정책 자체가 도는지를
	 * 재는 것이라 제한 역할 커넥션에 직접 넣는다.
	 */
	private <T> T asTeacher(java.util.function.Supplier<T> body) {
		return restrictedTx.execute(status -> {
			setConfig("checkon.current_teacher_id", teacherId.toString());
			return body.get();
		});
	}

	private void setConfig(String name, String value) {
		restricted.queryForObject("SELECT set_config(?, ?, true)", String.class, name, value);
	}

	private UUID insertAccount(String email, String role, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO accounts (id, email, role, status, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?)", id, email, role, now);
		return id;
	}

	private UUID insertParentProfile(String email, OffsetDateTime now) {
		UUID accountId = insertAccount(email, "PARENT", now);
		UUID profileId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?)", profileId, accountId, now, now);
		return profileId;
	}

	private void link(UUID parentId, UUID studentId, OffsetDateTime now) {
		jdbcTemplate.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentId, studentId, now, now);
	}

	private void teacherLink(UUID teacher, UUID studentId, OffsetDateTime now) {
		jdbcTemplate.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacher, studentId, now, now);
	}

	private UUID insertReport(UUID studentId, int revision, String status, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		OffsetDateTime publishedAt = "PUBLISHED".equals(status) ? now : null;
		String failureReason = "FAILED".equals(status) ? "renderer crashed" : null;
		jdbcTemplate.update("INSERT INTO member_published_reports (id, student_id, teacher_id,"
			+ " report_month, month_zone, revision, status, snapshot_version, created_at,"
			+ " updated_at, published_at, failure_reason)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			id, studentId, teacherId, MONTH, ZONE, revision, status, VERSION,
			now, now, publishedAt, failureReason);
		return id;
	}

	private int insertSection(
		UUID reportId, UUID studentId, OffsetDateTime publishedAt,
		String kind, String status, String unproducedReason
	) {
		return jdbcTemplate.update("INSERT INTO member_published_report_sections (id, report_id,"
			+ " student_id, teacher_id, published_at, kind, title, ordinal, status, body,"
			+ " content, evidence_refs, unproduced_reason, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, '요약', 0, ?, '본문', NULL, '[]'::jsonb, ?, ?)",
			UUID.randomUUID(), reportId, studentId, teacherId, publishedAt, kind, status,
			unproducedReason, OffsetDateTime.now(ZoneOffset.UTC));
	}

	private void insertFile(UUID reportId, UUID studentId, OffsetDateTime now) {
		jdbcTemplate.update("INSERT INTO member_report_files (id, report_id, student_id,"
			+ " teacher_id, published_at, object_key, checksum, content_type, size_bytes,"
			+ " page_count, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'reports/x.pdf', ?, 'application/pdf', 10, NULL, ?)",
			UUID.randomUUID(), reportId, studentId, teacherId, now, CHECKSUM, now);
	}

	private void insertOutbox(UUID reportId, UUID studentId, OffsetDateTime now) {
		jdbcTemplate.update("INSERT INTO member_report_publication_outbox (id, report_id,"
			+ " student_id, teacher_id, published_at, status, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
			UUID.randomUUID(), reportId, studentId, teacherId, now, now);
	}

	private javax.sql.DataSource restrictedDataSource() {
		restrictedJdbcTemplate(jdbcTemplate);
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(RESTRICTED_ROLE);
		dataSource.setPassword(RESTRICTED_PASSWORD);
		return dataSource;
	}
}
