package com.checkon.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import com.checkon.member.support.MemberPostgresSupport;
import org.springframework.test.web.servlet.MockMvc;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;

/**
 * 세션 bootstrap · 활성화 폴링 · 대기 학생 guard. 분기표 §1 의 세션 5 · 활성화 4 에 대응한다.
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MemberSessionIntegrationTest extends MemberPostgresSupport {

	private static final String SESSION = "/api/v1/member/auth/session";
	private static final String ACTIVATION = "/api/v1/member/auth/students/activation-status";
	private static final String INVITATIONS = "/api/v1/member/students/me/invitations";

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbcTemplate;

	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID parentAccountId;
	private UUID teacherId;

	@BeforeEach
	void setUp() {
		clearMemberFixtures(jdbcTemplate);

		OffsetDateTime now = OffsetDateTime.now();
		studentAccountId = insertAccount("student@example.com", "STUDENT", now);
		studentProfileId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO student_profiles (id, account_id, alias, grade, account_linked_at,"
				+ " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			studentProfileId, studentAccountId, "김학생", (short) 2, now, now, now);
		jdbcTemplate.update(
			"INSERT INTO member_display_names (account_id, display_name, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?)", studentAccountId, "김학생", now, now);
		jdbcTemplate.update(
			"INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
				+ " VALUES (?, ?, ?)", studentProfileId, "STU-TEST01", now);
		jdbcTemplate.update(
			"INSERT INTO member_student_activation (student_id, status, activated_at,"
				+ " created_at, updated_at) VALUES (?, 'PENDING_PARENT_LINK', NULL, ?, ?)",
			studentProfileId, now, now);

		parentAccountId = insertAccount("parent@example.com", "PARENT", now);
		jdbcTemplate.update(
			"INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?)", UUID.randomUUID(), parentAccountId, now, now);
		jdbcTemplate.update(
			"INSERT INTO member_display_names (account_id, display_name, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?)", parentAccountId, "박학부모", now, now);

		UUID teacherAccountId = insertAccount("teacher@example.com", "TEACHER", now);
		teacherId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO teacher_profiles (id, account_id, display_name, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?)", teacherId, teacherAccountId, "김강사", now, now);
	}

	@Test
	@DisplayName("🔴 학생 세션 — activationStatus 가 문자열 PENDING_PARENT_LINK 로 나간다")
	void studentSessionSerialisesActivationAsString() throws Exception {
		mockMvc.perform(get(SESSION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("STUDENT"))
			.andExpect(jsonPath("$.data.accountId").value(studentAccountId.toString()))
			.andExpect(jsonPath("$.data.studentProfileId").value(studentProfileId.toString()))
			// 🔴 enum 이 숫자나 객체로 나가면 프론트 분기가 조용히 깨진다.
			.andExpect(jsonPath("$.data.activationStatus").value("PENDING_PARENT_LINK"))
			.andExpect(jsonPath("$.data.studentPublicId").value("STU-TEST01"))
			.andExpect(jsonPath("$.data.name").value("김학생"));
	}

	@Test
	@DisplayName("🔴 MB-32 CLOSED — 세션 응답이 notificationsEnabled 키를 채운다 (V41)")
	void sessionIncludesNotificationsEnabled() throws Exception {
		// 🔴 PR6/V41 이 member_notification_preferences 를 만들었다. 부재 시 Settings 기본값
		//    (checkon.member.notification.default-enabled=true) 로 대체한다.
		mockMvc.perform(get(SESSION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notificationsEnabled").value(true));
	}

	@Test
	@DisplayName("🔴 결함 3 재현 — 강사 관계가 있으면 teachers 가 비어 있지 않다")
	void sessionReturnsLinkedTeachers() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		jdbcTemplate.update(
			"INSERT INTO teacher_student_relationships (id, teacher_id, student_id, status,"
				+ " started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);

		mockMvc.perform(get(SESSION).with(student()))
			.andExpect(status().isOk())
			// 🔴 빈 배열도 200 이라 「200 이 온다」로는 이 결함을 못 잡는다.
			.andExpect(jsonPath("$.data.teachers.length()").value(1))
			.andExpect(jsonPath("$.data.teachers[0].teacherId").value(teacherId.toString()))
			.andExpect(jsonPath("$.data.teachers[0].displayName").value("김강사"))
			// subject 는 계약 :1648-1650 대로 항상 null 이다. 키는 있어야 한다.
			.andExpect(jsonPath("$.data.teachers[0].subject").doesNotExist());
	}

	@Test
	@DisplayName("연결 강사 0명이어도 200 과 빈 배열이다 — 오류가 아니다")
	void sessionWithoutTeachersIsStillOk() throws Exception {
		mockMvc.perform(get(SESSION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.teachers.length()").value(0));
	}

	@Test
	@DisplayName("🔴 결함 1 재현 — 학부모 세션이 401 이 아니라 200 이고 parentProfileId 가 실제로 있다")
	void parentSessionResolvesProfile() throws Exception {
		mockMvc.perform(get(SESSION).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("PARENT"))
			.andExpect(jsonPath("$.data.parentProfileId").isNotEmpty())
			.andExpect(jsonPath("$.data.studentProfileId").doesNotExist())
			.andExpect(jsonPath("$.data.activationStatus").doesNotExist())
			.andExpect(jsonPath("$.data.name").value("박학부모"));
	}

	@Test
	@DisplayName("프로필이 없는 계정은 401 AUTHENTICATION_REQUIRED — 존재를 404 로 노출하지 않는다")
	void accountWithoutProfileIsUnauthenticated() throws Exception {
		UUID orphan = insertAccount("orphan@example.com", "STUDENT", OffsetDateTime.now());

		mockMvc.perform(get(SESSION).with(principal(orphan, AccountRole.STUDENT)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	@DisplayName("활성화 상태 조회 — 대기 중에는 activatedAt 이 null 이다")
	void activationStatusWhilePending() throws Exception {
		mockMvc.perform(get(ACTIVATION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("PENDING_PARENT_LINK"))
			.andExpect(jsonPath("$.data.studentPublicId").value("STU-TEST01"))
			.andExpect(jsonPath("$.data.activatedAt").doesNotExist());
	}

	@Test
	@DisplayName("활성화 상태 조회 — ACTIVE 면 activatedAt 이 채워진다")
	void activationStatusWhenActive() throws Exception {
		activate();

		mockMvc.perform(get(ACTIVATION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.activatedAt").isNotEmpty());
	}

	@Test
	@DisplayName("🔴 학부모가 활성화 상태를 부르면 403 ROLE_FORBIDDEN")
	void parentCannotReadActivationStatus() throws Exception {
		mockMvc.perform(get(ACTIVATION).with(parent()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"));
	}

	@Test
	@DisplayName("🔴 MB-02 — 대기 학생이 초대 등록을 부르면 403 STUDENT_ACTIVATION_REQUIRED")
	void pendingStudentCannotRegisterInvitation() throws Exception {
		mockMvc.perform(post(INVITATIONS).with(student())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"code\":\"ABC123\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));
	}

	@Test
	@DisplayName("🔴 MB-02 — 대기 학생도 세션과 활성화 상태 2개는 통과한다")
	void pendingStudentReachesTheTwoAllowedPaths() throws Exception {
		mockMvc.perform(get(SESSION).with(student())).andExpect(status().isOk());
		mockMvc.perform(get(ACTIVATION).with(student())).andExpect(status().isOk());
	}

	@Test
	@DisplayName("🔴 fail-closed — 활성화 행이 없으면 허용 목록까지 포함해 전부 403 이다")
	void missingActivationRowIsForbiddenEverywhere() throws Exception {
		jdbcTemplate.update("DELETE FROM member_student_activation WHERE student_id = ?",
			studentProfileId);

		mockMvc.perform(post(INVITATIONS).with(student())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"code\":\"ABC123\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));

		// 🔴 DEACTIVATED 와 갈리는 지점이다. null 은 보고할 수 있는 상태가 아니다 —
		//    허용 목록의 두 경로도 막는다.
		mockMvc.perform(get(ACTIVATION).with(student()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(SESSION).with(student()))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("🔴 DEACTIVATED 학생은 활성화 상태를 200 으로 볼 수 있다 (분기표 :144)")
	void deactivatedStudentCanStillReadItsOwnStatus() throws Exception {
		deactivate();

		// 🔴 여기서 403 을 내면 비활성화된 학생이 자기가 왜 못 쓰는지 볼 방법이 없다.
		//    이 엔드포인트는 상태를 알려주는 것이 일 자체다.
		mockMvc.perform(get(ACTIVATION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DEACTIVATED"));
		mockMvc.perform(get(SESSION).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.activationStatus").value("DEACTIVATED"));
	}

	@Test
	@DisplayName("🔴 DEACTIVATED 학생은 그 밖의 member 경로에서는 403 이다")
	void deactivatedStudentIsBlockedElsewhere() throws Exception {
		deactivate();

		mockMvc.perform(post(INVITATIONS).with(student())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"code\":\"ABC123\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));
	}

	@Test
	@DisplayName("활성화된 학생은 guard 를 통과한다 — 403 이 아니다")
	void activeStudentPassesTheGuard() throws Exception {
		activate();

		int statusCode = mockMvc.perform(post(INVITATIONS).with(student())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"code\":\"ABC123\"}"))
			.andReturn().getResponse().getStatus();

		// 🔴 초대 등록 자체는 PR4 소유라 404 여도 된다. 여기서 보는 건 guard 가 안 막는다는 것뿐이다.
		assertThat(statusCode)
			.as("활성 학생이 403 이면 guard 가 상태를 잘못 읽은 것이다")
			.isNotEqualTo(403);
	}

	@Test
	@DisplayName("🔴 guard 는 member 경로 밖으로 새지 않는다 — 기존 강사 API 는 그대로다")
	void guardDoesNotLeakOutsideMemberPaths() throws Exception {
		int statusCode = mockMvc.perform(get("/api/v1/dashboard/briefing")).andReturn()
			.getResponse().getStatus();

		assertThat(statusCode).as("기존 API 가 member guard 로 403 이 되면 무접촉 위반이다")
			.isNotEqualTo(403);
	}

	private void deactivate() {
		jdbcTemplate.update(
			"UPDATE member_student_activation SET status = 'DEACTIVATED' WHERE student_id = ?",
			studentProfileId);
	}

	private void activate() {
		jdbcTemplate.update(
			"UPDATE member_student_activation SET status = 'ACTIVE', activated_at = ?"
				+ " WHERE student_id = ?", OffsetDateTime.now(), studentProfileId);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor principal(
		UUID accountId, AccountRole role
	) {
		return authentication(principalOf(accountId, role));
	}

	private Authentication principalOf(UUID accountId, AccountRole role) {
		AuthenticatedAccount account = new AuthenticatedAccount(
			accountId, role, null, UUID.randomUUID());
		return new UsernamePasswordAuthenticationToken(
			account, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
	}

	private UUID insertAccount(String email, String role, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO accounts (id, email, role, status, created_at) VALUES (?, ?, ?, ?, ?)",
			id, email, role, "ACTIVE", now);
		return id;
	}
}
