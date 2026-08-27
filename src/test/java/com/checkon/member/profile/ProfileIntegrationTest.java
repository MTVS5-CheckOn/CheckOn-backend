package com.checkon.member.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** PR6 §4 프로필 API 분기 검증. */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ProfileIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String STUDENT_PROFILE = "/api/v1/member/students/me/profile";
	private static final String STUDENT_PREF = STUDENT_PROFILE + "/notification-preference";
	private static final String PARENT_PROFILE = "/api/v1/member/parents/me/profile";
	private static final String PARENT_PREF = PARENT_PROFILE + "/notification-preference";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID teacherId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now();

		assertThat(applicationRolePrivileges()).isEqualTo("false/false");

		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = insertStudentFull(studentAccountId, "김학생", "STU-QQQ111", 2);
		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);

		teacherId = insertTeacher(admin, "teacher@example.com", "박강사", now);
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);
		admin.update("UPDATE member_student_activation SET status = 'ACTIVE', activated_at = ?"
			+ " WHERE student_id = ?", now, studentProfileId);
	}

	@Test
	@DisplayName("🔴 이름은 member_display_names 만 읽는다 — student_profiles.alias 와 갈라 놔도 표시명이 이긴다")
	void nameComesFromDisplayNames() throws Exception {
		// 🔴 두 값이 같으면 이 테스트는 아무것도 증명하지 않는다 — 갈라놓는다.
		admin.update("UPDATE student_profiles SET alias = ? WHERE id = ?",
			"3반 김", studentProfileId);
		admin.update("UPDATE member_display_names SET display_name = ? WHERE account_id = ?",
			"김체크", studentAccountId);

		mockMvc.perform(get(STUDENT_PROFILE).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("김체크"));
	}

	@Test
	@DisplayName("🔴 프로필·설정 호출 후 student_profiles.alias 는 불변")
	void aliasNotMutated() throws Exception {
		String before = admin.queryForObject(
			"SELECT alias FROM student_profiles WHERE id = ?", String.class, studentProfileId);
		mockMvc.perform(get(STUDENT_PROFILE).with(student())).andExpect(status().isOk());
		mockMvc.perform(patch(STUDENT_PREF).with(student())
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
			.andExpect(status().isNoContent());
		String after = admin.queryForObject(
			"SELECT alias FROM student_profiles WHERE id = ?", String.class, studentProfileId);
		assertThat(after).isEqualTo(before);
	}

	@Test
	@DisplayName("🔴 parentLinked 는 activationStatus=ACTIVE 로 파생 · PENDING 은 false")
	void parentLinkedFromActivation() throws Exception {
		mockMvc.perform(get(STUDENT_PROFILE).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.parentLinked").value(true));

		admin.update("UPDATE member_student_activation SET status = 'PENDING_PARENT_LINK',"
			+ " activated_at = NULL WHERE student_id = ?", studentProfileId);
		// 🔴 대기 학생은 학습 경로 전부 403 이지만 프로필도 그 목록에 없다.
		//    activation-status 폴링과 세션만 허용된다(§4-4). 프로필은 원래 403 이 맞다.
		mockMvc.perform(get(STUDENT_PROFILE).with(student()))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("🔴 TeacherSummary 에 academyName 필드가 없고 subject 는 null")
	void teacherSummaryHasNoAcademyName() throws Exception {
		String body = mockMvc.perform(get(STUDENT_PROFILE).with(student()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("academyName");
		// subject 는 키가 존재하지만 값은 null.
		assertThat(body).contains("\"subject\":null");
	}

	@Test
	@DisplayName("🔴 preference 행 부재 → Settings 기본값 · PATCH 후 반영 · 2회 PATCH 는 1행")
	void preferenceDefaultsWhenRowMissing() throws Exception {
		// 부재 → true (기본값)
		mockMvc.perform(get(STUDENT_PROFILE).with(student()))
			.andExpect(jsonPath("$.data.notificationsEnabled").value(true));

		mockMvc.perform(patch(STUDENT_PREF).with(student())
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
			.andExpect(status().isNoContent());

		mockMvc.perform(get(STUDENT_PROFILE).with(student()))
			.andExpect(jsonPath("$.data.notificationsEnabled").value(false));

		mockMvc.perform(patch(STUDENT_PREF).with(student())
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
			.andExpect(status().isNoContent());

		Integer count = admin.queryForObject(
			"SELECT count(*)::int FROM member_notification_preferences WHERE account_id = ?",
			Integer.class, studentAccountId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 학부모 프로필 — children 이 PR4 assembler 그대로")
	void parentProfileReturnsChildren() throws Exception {
		// 학부모↔자녀 관계
		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentProfileId, studentProfileId, now, now);

		mockMvc.perform(get(PARENT_PROFILE).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("박학부모"))
			.andExpect(jsonPath("$.data.email").value("parent@example.com"))
			.andExpect(jsonPath("$.data.children[0].studentId").value(studentProfileId.toString()));
	}

	@Test
	@DisplayName("🔴 PATCH 에 enabled 없으면 400")
	void patchWithoutEnabledIs400() throws Exception {
		mockMvc.perform(patch(PARENT_PREF).with(parent())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
	}

	// ── 헬퍼 ──────────────────────────────

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

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}
}
