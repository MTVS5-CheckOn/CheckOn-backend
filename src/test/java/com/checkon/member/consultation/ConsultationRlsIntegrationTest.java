package com.checkon.member.consultation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.support.MemberPostgresSupport;

@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class ConsultationRlsIntegrationTest extends MemberPostgresSupport {

	@Autowired JdbcTemplate admin;

	private JdbcTemplate restricted;
	private TransactionTemplate restrictedTx;
	private UUID parentProfileId;
	private UUID otherParentProfileId;
	private UUID studentProfileId;
	private UUID teacherId;
	private UUID consultationId;

	@BeforeEach
	void setUp() {
		clearMemberFixtures(admin);
		restricted = restrictedJdbcTemplate(admin);
		restrictedTx = new TransactionTemplate(
			new JdbcTransactionManager(restricted.getDataSource()));
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

		UUID parentAccount = insertAccount("parent@example.com", "PARENT", now);
		UUID otherParentAccount = insertAccount("other-parent@example.com", "PARENT", now);
		UUID studentAccount = insertAccount("student@example.com", "STUDENT", now);
		UUID teacherAccount = insertAccount("teacher@example.com", "TEACHER", now);

		parentProfileId = insertParent(parentAccount, now);
		otherParentProfileId = insertParent(otherParentAccount, now);
		studentProfileId = insertStudentProfile(admin, studentAccount, "김학생", 2, now);
		teacherId = UUID.randomUUID();
		admin.update("INSERT INTO teacher_profiles"
			+ " (id, account_id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
			teacherId, teacherAccount, "박강사", now, now);

		consultationId = UUID.randomUUID();
		admin.update("INSERT INTO member_consultations"
			+ " (id, parent_id, student_id, teacher_id, content, masked_content, status,"
			+ " ai_status, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?, '원문', '마스킹', 'SUBMITTED', 'NOT_REQUESTED', ?, ?)",
			consultationId, parentProfileId, studentProfileId, teacherId, now, now);
	}

	@Test
	void 전제1_제한_역할이_실제로_RLS_대상이다() {
		assertThat(privilegeFlags(restricted)).isEqualTo("false/false");
	}

	@Test
	void 전제2_상담_행이_admin에게는_실제로_보인다() {
		Integer count = admin.queryForObject(
			"SELECT count(*) FROM member_consultations WHERE id=?",
			Integer.class, consultationId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void 다른_학부모_컨텍스트는_상담_행을_볼_수_없다() {
		List<UUID> visible = restrictedTx.execute(status -> {
			setConfig("checkon.current_parent_id", otherParentProfileId);
			return restricted.queryForList(
				"SELECT id FROM member_consultations WHERE id=?",
				UUID.class, consultationId);
		});
		assertThat(visible).isEmpty();
	}

	@Test
	void 학부모는_발행_메시지를_INSERT할_수_없다() {
		assertThatThrownBy(() -> restrictedTx.executeWithoutResult(status -> {
			setConfig("checkon.current_parent_id", parentProfileId);
			restricted.update("INSERT INTO member_consultation_messages"
				+ " (id, consultation_id, parent_id, student_id, teacher_id, author_role,"
				+ " content, published_at, created_at)"
				+ " VALUES (?, ?, ?, ?, ?, 'PARENT', '위조', now(), now())",
				UUID.randomUUID(), consultationId, parentProfileId, studentProfileId, teacherId);
		})).rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void 발행_메시지는_publishedAt이_반드시_있다() {
		String nullable = admin.queryForObject("""
			SELECT is_nullable
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'member_consultation_messages'
			  AND column_name = 'published_at'
			""", String.class);

		assertThat(nullable).isEqualTo("NO");
	}

	private UUID insertAccount(String email, String role, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO accounts (id, email, role, status, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?)", id, email, role, now);
		return id;
	}

	private UUID insertParent(UUID accountId, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?)", id, accountId, now, now);
		return id;
	}

	private void setConfig(String name, UUID value) {
		restricted.queryForObject(
			"SELECT set_config(?, ?, true)", String.class, name, value.toString());
	}
}
