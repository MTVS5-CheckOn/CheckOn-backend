package com.checkon.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;

/** PR6 §5·§6·§7 알림 발행·목록·읽음 분기 검증. */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class NotificationIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String NOTIFICATIONS = "/api/v1/member/parents/me/notifications";
	private static final String CHILDREN = "/api/v1/member/parents/me/children";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID otherParentAccountId;
	private UUID otherParentProfileId;
	private UUID studentAccountId;
	private UUID studentProfileId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now();

		assertThat(applicationRolePrivileges()).isEqualTo("false/false");

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);
		otherParentAccountId = insertAccount(admin, "other-parent@example.com", "PARENT", now);
		otherParentProfileId = insertParent(admin, otherParentAccountId, "이학부모", now);

		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = insertStudentFull(studentAccountId, "김학생", "STU-NNN111", 2);
	}

	@Test
	@DisplayName("🔴 자녀 등록 트랜잭션이 CHILD_LINKED 1행을 발행한다")
	void childLinkPublishesOnce() throws Exception {
		String body = "{\"studentPublicId\":\"STU-NNN111\"}";
		mockMvc.perform(post(CHILDREN).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());

		Integer count = admin.queryForObject(
			"SELECT count(*)::int FROM member_notifications"
				+ " WHERE recipient_account_id = ? AND type = 'CHILD_LINKED'",
			Integer.class, parentAccountId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 같은 (source_type, source_id, recipient) 는 unique 로 무시된다")
	void duplicateSourceIsIgnored() {
		UUID sourceId = UUID.randomUUID();
		admin.update("INSERT INTO member_notifications"
			+ " (id, recipient_account_id, type, title, body, target_student_id,"
			+ "  target_resource_id, source_type, source_id, read_at, created_at)"
			+ " VALUES (?, ?, 'CHILD_LINKED', '자녀 등록', NULL, NULL, NULL, ?, ?, NULL, ?)",
			UUID.randomUUID(), parentAccountId, "parent_student_relationship", sourceId, now);
		// 같은 (source_type, source_id, recipient) 로 재 INSERT — ON CONFLICT DO NOTHING.
		int updated = admin.update("INSERT INTO member_notifications"
			+ " (id, recipient_account_id, type, title, body, target_student_id,"
			+ "  target_resource_id, source_type, source_id, read_at, created_at)"
			+ " VALUES (?, ?, 'CHILD_LINKED', '자녀 등록', NULL, NULL, NULL, ?, ?, NULL, ?)"
			+ " ON CONFLICT ON CONSTRAINT uq_member_notifications_source DO NOTHING",
			UUID.randomUUID(), parentAccountId, "parent_student_relationship", sourceId, now);
		assertThat(updated).isZero();

		Integer count = admin.queryForObject(
			"SELECT count(*)::int FROM member_notifications WHERE recipient_account_id = ?",
			Integer.class, parentAccountId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 limit > 50 → 400 · 깨진 cursor → 400")
	void listRejectsInvalidLimitAndCursor() throws Exception {
		mockMvc.perform(get(NOTIFICATIONS + "?limit=51").with(parent()))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get(NOTIFICATIONS + "?cursor=broken").with(parent()))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("🔴 read 2회는 둘 다 204 · read_at 은 첫 값으로 불변")
	void readIsIdempotent() throws Exception {
		UUID notificationId = insertNotification(parentAccountId, now.minusHours(1));
		mockMvc.perform(post(NOTIFICATIONS + "/" + notificationId + "/read").with(parent()))
			.andExpect(status().isNoContent());
		OffsetDateTime firstReadAt = admin.queryForObject(
			"SELECT read_at FROM member_notifications WHERE id = ?",
			OffsetDateTime.class, notificationId);

		// 두 번째 read — 여전히 204, read_at 불변.
		mockMvc.perform(post(NOTIFICATIONS + "/" + notificationId + "/read").with(parent()))
			.andExpect(status().isNoContent());
		OffsetDateTime secondReadAt = admin.queryForObject(
			"SELECT read_at FROM member_notifications WHERE id = ?",
			OffsetDateTime.class, notificationId);
		assertThat(secondReadAt).isEqualTo(firstReadAt);
	}

	@Test
	@DisplayName("🔴 남의 학부모 알림 id → 404 · read_at NULL 유지")
	void otherParentsNotificationIs404() throws Exception {
		UUID otherNotificationId = insertNotification(otherParentAccountId, now);
		mockMvc.perform(post(NOTIFICATIONS + "/" + otherNotificationId + "/read").with(parent()))
			.andExpect(status().isNotFound());
		OffsetDateTime readAt = admin.queryForObject(
			"SELECT read_at FROM member_notifications WHERE id = ?",
			OffsetDateTime.class, otherNotificationId);
		assertThat(readAt).isNull();
	}

	@Test
	@DisplayName("🔴 목록은 recipient self 로만 보인다")
	void listOnlyShowsOwnNotifications() throws Exception {
		insertNotification(parentAccountId, now);
		insertNotification(otherParentAccountId, now);
		mockMvc.perform(get(NOTIFICATIONS).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1));
	}

	// ── 헬퍼 ──────────────────────────────

	private UUID insertNotification(UUID recipientId, OffsetDateTime createdAt) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO member_notifications"
			+ " (id, recipient_account_id, type, title, body, target_student_id,"
			+ "  target_resource_id, source_type, source_id, read_at, created_at)"
			+ " VALUES (?, ?, 'CHILD_LINKED', '자녀 등록', NULL, NULL, NULL, ?, ?, NULL, ?)",
			id, recipientId, "parent_student_relationship", UUID.randomUUID(), createdAt);
		return id;
	}

	private UUID insertStudentFull(UUID accountId, String name, String publicId, int grade) {
		UUID profileId = UUID.randomUUID();
		admin.update("INSERT INTO student_profiles (id, account_id, alias, grade,"
			+ " account_linked_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			profileId, accountId, name, grade, now, now, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name,"
			+ " created_at, updated_at) VALUES (?, ?, ?, ?)", accountId, name, now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", profileId, publicId, now);
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'PENDING_PARENT_LINK', NULL, ?, ?)",
			profileId, now, now);
		return profileId;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}
}
