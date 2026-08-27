package com.checkon.member.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;

/**
 * membership 7개 오퍼레이션의 분기 검증. 🔴 <b>제한 DB 역할</b> 위에서 돈다
 * ({@link MembershipRlsEnforcedSupport}) — superuser 로 돌면 사전 조회가 남의 관계까지 보게 되어
 * unique index 경로가 실행되지 않고, 그러면 이 스위트는 실제 동작이 아닌 것을 검증한다.
 *
 * <p>🔴 상한을 <b>런타임에</b> 1000 으로 올린다. 리미터는 컨텍스트 싱글턴이고 MockMvc 요청은
 * 전부 같은 IP(127.0.0.1)라 테스트끼리 카운터를 공유한다. 예전에는 컨텍스트 프로퍼티로 갈랐지만
 * 그 조합마다 스프링이 새로 떠서(G17), 이제는 같은 컨텍스트에서 오버라이드로 처리한다.
 * 429 분기는 {@link MembershipRateLimitIntegrationTest} 가 낮은 상한으로 따로 증명한다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MembershipIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String CHILDREN = "/api/v1/member/parents/me/children";
	private static final String CHILD_VERIFICATION = CHILDREN + "/verification";
	private static final String STUDENT_INVITATIONS = "/api/v1/member/students/me/invitations";
	private static final String STUDENT_INVITE_VERIFICATION =
		STUDENT_INVITATIONS + "/verification";
	private static final String PARENT_INVITATIONS = "/api/v1/member/parents/me/invitations";
	private static final String PARENT_INVITE_VERIFICATION = PARENT_INVITATIONS + "/verification";

	private static final String STUDENT_CODE = "STUDENT-CODE-1";
	private static final String PARENT_CODE = "PARENT-CODE-1";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;

	private UUID childAccountId;
	private UUID childProfileId;
	private UUID otherChildProfileId;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID otherParentAccountId;
	private UUID otherParentProfileId;
	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID teacherId;
	private UUID otherTeacherId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		clearFixtures(admin);
		now = OffsetDateTime.now();

		childAccountId = insertAccount(admin, "child@example.com", "STUDENT", now);
		childProfileId = insertStudent(admin, childAccountId,
			new StudentFixture("김민수", "김민수", 2, "STU-CHILD1"), now);

		UUID otherChildAccountId = insertAccount(admin, "child2@example.com", "STUDENT", now);
		otherChildProfileId = insertStudent(admin, otherChildAccountId,
			new StudentFixture("이영희", "이영희", 3, "STU-CHILD2"), now);

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);
		otherParentAccountId = insertAccount(admin, "parent2@example.com", "PARENT", now);
		otherParentProfileId = insertParent(admin, otherParentAccountId, "최학부모", now);

		// 초대 등록을 시험할 별도 학생 — 활성 상태여야 MB-02 guard 를 통과한다.
		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = insertStudent(admin, studentAccountId,
			new StudentFixture("정학생", "정학생", 1, "STU-STUD01"), now);
		activate(studentProfileId);

		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		otherTeacherId = insertTeacher(admin, "teacher2@example.com", "이강사", now);

		// 🔴 공유 컨텍스트라 앞 테스트의 카운터·오버라이드가 남아 있을 수 있다 — 지우고 올린다.
		rateLimiter.resetForTesting();
		rateLimiter.overridePermitsForTesting(1000);
	}

	@AfterEach
	void tearDown() {
		rateLimiter.resetForTesting();
	}

	// ───────────────────────────── 전제 ─────────────────────────────

	@Test
	@DisplayName("🔴 전제 — 애플리케이션 커넥션이 정말 RLS 대상이다")
	void applicationRoleIsSubjectToRowLevelSecurity() {
		// 이 단언이 없으면 아래 모든 RLS 의존 단언이 조용히 무의미해진다(MB-34).
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");
	}

	// ────────────────────── GET /parents/me/children ──────────────────────

	@Test
	@DisplayName("자녀 목록은 내 활성 관계만 돌려준다 — 남의 자녀·ENDED 는 0건")
	void childrenListReturnsOnlyActiveLinks() throws Exception {
		linkParentToChild(parentProfileId, childProfileId, "ACTIVE");
		linkParentToChild(otherParentProfileId, otherChildProfileId, "ACTIVE");
		UUID endedChild = insertStudent(admin,
			insertAccount(admin, "child3@example.com", "STUDENT", now),
			new StudentFixture("한종료", "한종료", 3, "STU-CHILD3"), now);
		admin.update("INSERT INTO parent_student_relationships (id, parent_id, student_id,"
			+ " status, started_at, ended_at, created_at) VALUES (?, ?, ?, 'ENDED', ?, ?, ?)",
			UUID.randomUUID(), parentProfileId, endedChild, now, now, now);

		mockMvc.perform(get(CHILDREN).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].studentId").value(childProfileId.toString()))
			.andExpect(jsonPath("$.data.items[0].studentPublicId").value("STU-CHILD1"))
			// 🔴 이름의 원본은 member_display_names 다 — 범위를 열지 않으면 여기가 null 이 된다.
			.andExpect(jsonPath("$.data.items[0].name").value("김민수"));
	}

	@Test
	@DisplayName("자녀가 0명이면 200 + 빈 배열이다 — 오류가 아니다")
	void childrenListIsEmptyWithoutLinks() throws Exception {
		mockMvc.perform(get(CHILDREN).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0));
	}

	@Test
	@DisplayName("DEACTIVATED 자녀도 목록에 남고 상태로 표시된다 — 숨기지 않는다")
	void childrenListKeepsDeactivatedChild() throws Exception {
		linkParentToChild(parentProfileId, childProfileId, "ACTIVE");
		admin.update("UPDATE member_student_activation SET status = 'DEACTIVATED',"
			+ " deactivated_at = ? WHERE student_id = ?", now, childProfileId);

		mockMvc.perform(get(CHILDREN).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].activationStatus").value("DEACTIVATED"));
	}

	@Test
	@DisplayName("🔴 teachers 키가 존재하고 활성 강사가 배열로 담긴다 (MB-36 · V40)")
	void teachersKeyIsPresentAndPopulated() throws Exception {
		linkParentToChild(parentProfileId, childProfileId, "ACTIVE");
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, childProfileId, now, now);
		// ENDED 관계는 배열에 없어야 한다.
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, ended_at, created_at) VALUES (?, ?, ?, 'ENDED', ?, ?, ?)",
			UUID.randomUUID(), otherTeacherId, childProfileId, now.minusDays(30), now, now);

		mockMvc.perform(get(CHILDREN).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].teachers.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].teachers[0].teacherId")
				.value(teacherId.toString()))
			.andExpect(jsonPath("$.data.items[0].teachers[0].displayName").value("김강사"));
	}

	@Test
	@DisplayName("🔴 자녀 등록 응답의 teachers 도 같은 조립기를 통해 존재한다 (MB-36 · V40)")
	void teachersKeyIsPresentInRegistrationResponse() throws Exception {
		// 등록 시점에는 아직 강사가 없다 — 빈 배열이지만 키는 존재해야 한다.
		// jsonPath 는 "키가 존재하고 배열이며 길이가 0" 을 정확히 잰다. 문자열 대조는 공백 하나에 갈린다.
		mockMvc.perform(registrationRequest("STU-CHILD1", UUID.randomUUID().toString()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.child.teachers").isArray())
			.andExpect(jsonPath("$.data.child.teachers.length()").value(0));
	}

	@Test
	@DisplayName("🔴 남의 자녀 id 를 범위에 넣어도 그 자녀의 강사는 안 보인다 (MB-36 정책 격리)")
	void teachersLookupIsScopedToVerifiedChild() throws Exception {
		// 이 학부모는 childProfileId 하고만 연결돼 있다.
		linkParentToChild(parentProfileId, childProfileId, "ACTIVE");
		// otherChildProfileId 는 이 학부모의 자녀가 아니다. 강사 관계도 만들어 둔다.
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, otherChildProfileId, now, now);

		// 목록에 남의 자녀는 안 나타나므로(정책이 이미 격리), 이 시나리오의 남단언은
		// 「내 자녀 목록에 남의 자녀의 강사가 새어 나오지 않는다」이다.
		mockMvc.perform(get(CHILDREN).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].studentId").value(childProfileId.toString()))
			.andExpect(jsonPath("$.data.items[0].teachers.length()").value(0));
	}

	@Test
	@DisplayName("🔴 grade 는 nullable 이라 키가 남고 값이 null 이다 — teachers 와 판정이 다르다")
	void nullableGradeKeepsItsKey() throws Exception {
		// 🔴 이 테스트가 막는 것: 「null 이면 키를 뺀다」를 레코드 전체에 거는 것(NON_NULL).
		//    grade 는 계약이 nullable: true 라 키가 사라지면 그쪽이 계약 위반이 된다.
		UUID noGradeAccount = insertAccount(admin, "nograde@example.com", "STUDENT", now);
		UUID noGradeStudent = insertStudent(admin, noGradeAccount,
			new StudentFixture("무학년", "무학년", null, "STU-NOGRD1"), now);
		linkParentToChild(parentProfileId, noGradeStudent, "ACTIVE");

		MvcResult listed = mockMvc.perform(get(CHILDREN).with(parent()))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(listed.getResponse().getContentAsString())
			.as("nullable 필드는 키를 남기고 null 을 담는다")
			.contains("\"grade\":null");
	}

	// ─────────────────── POST /children/verification ───────────────────

	@Test
	@DisplayName("사전 확인은 마스킹된 이름만 돌려준다 — 원본 alias 와 다르다")
	void verificationReturnsMaskedNameOnly() throws Exception {
		String alias = admin.queryForObject(
			"SELECT alias FROM student_profiles WHERE id = ?", String.class, childProfileId);

		MvcResult result = mockMvc.perform(verificationRequest("STU-CHILD1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.registrable").value(true))
			.andExpect(jsonPath("$.data.grade").value(2))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).as("원본 이름이 그대로 나가면 안 된다").doesNotContain(alias);
		assertThat(body).contains("김*수");
	}

	@Test
	@DisplayName("이미 내가 연결한 자녀는 registrable=false ALREADY_LINKED 다")
	void verificationReportsMyOwnLink() throws Exception {
		linkParentToChild(parentProfileId, childProfileId, "ACTIVE");

		mockMvc.perform(verificationRequest("STU-CHILD1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.registrable").value(false))
			.andExpect(jsonPath("$.data.reason").value("ALREADY_LINKED"))
			.andExpect(jsonPath("$.data.name").doesNotExist());
	}

	@Test
	@DisplayName("🔴 다른 학부모의 연결은 사전 확인에 보이지 않는다 (MB-37)")
	void verificationCannotSeeAnotherParentsLink() throws Exception {
		linkParentToChild(otherParentProfileId, childProfileId, "ACTIVE");

		// 정책이 남의 행을 가리므로 "등록 가능"으로 보인다. 최종 판정은 등록의 409 다.
		mockMvc.perform(verificationRequest("STU-CHILD1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.registrable").value(true));
	}

	@Test
	@DisplayName("없는 공개 ID 는 404, 형식 오류는 400 이다")
	void verificationSeparatesMissingFromMalformed() throws Exception {
		mockMvc.perform(verificationRequest("STU-ZZZZZZ"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

		mockMvc.perform(verificationRequest("not-a-public-id"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.details[0].field").value("studentPublicId"));
	}

	@Test
	@DisplayName("공백·소문자·하이픈 없는 입력도 같은 학생으로 정규화된다")
	void verificationNormalizesPublicId() throws Exception {
		mockMvc.perform(verificationRequest(" stu child1 "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.registrable").value(true));
	}

	// ───────────────────────── POST /children ─────────────────────────

	@Test
	@DisplayName("🔴 자녀 등록이 학생을 ACTIVE 로 전이시킨다 — activated_at 도 채운다")
	void registerChildActivatesStudent() throws Exception {
		mockMvc.perform(registrationRequest("STU-CHILD1", UUID.randomUUID().toString()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.child.studentId").value(childProfileId.toString()))
			.andExpect(jsonPath("$.data.child.activationStatus").value("ACTIVE"))
			.andExpect(jsonPath("$.data.child.name").value("김민수"));

		Map<String, Object> activation = admin.queryForMap(
			"SELECT status, activated_at FROM member_student_activation WHERE student_id = ?",
			childProfileId);
		assertThat(activation.get("status")).isEqualTo("ACTIVE");
		assertThat(activation.get("activated_at")).as("activated_at 이 비면 전이가 반쪽이다")
			.isNotNull();
		assertThat(countActiveLinks(childProfileId)).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 다른 학부모가 이미 연결한 학생은 409 이고 그 학부모를 노출하지 않는다")
	void registerChildRejectsSecondParent() throws Exception {
		linkParentToChild(otherParentProfileId, childProfileId, "ACTIVE");

		MvcResult result = mockMvc.perform(
				registrationRequest("STU-CHILD1", UUID.randomUUID().toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CHILD_ALREADY_LINKED"))
			// 사전 조회로는 못 봤고 unique index 가 막았다는 뜻이다.
			.andExpect(jsonPath("$.error.details.alreadyMine").value(false))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).as("기존 학부모 식별자가 응답에 새면 안 된다")
			.doesNotContain(otherParentProfileId.toString())
			.doesNotContain(otherParentAccountId.toString());
		assertThat(countActiveLinks(childProfileId)).isEqualTo(1);
	}

	@Test
	@DisplayName("내가 이미 연결한 자녀를 다시 등록하면 409 + alreadyMine:true")
	void registerChildReportsMyOwnLink() throws Exception {
		linkParentToChild(parentProfileId, childProfileId, "ACTIVE");

		mockMvc.perform(registrationRequest("STU-CHILD1", UUID.randomUUID().toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CHILD_ALREADY_LINKED"))
			.andExpect(jsonPath("$.error.details.alreadyMine").value(true));
	}

	@Test
	@DisplayName("없는 공개 ID 로 등록하면 404 다")
	void registerChildRejectsMissingStudent() throws Exception {
		mockMvc.perform(registrationRequest("STU-ZZZZZZ", UUID.randomUUID().toString()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("Idempotency-Key 가 없으면 400 이고 어느 헤더인지 알려준다")
	void registerChildRequiresIdempotencyKey() throws Exception {
		mockMvc.perform(post(CHILDREN).with(parent())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"STU-CHILD1\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.details[0].field").value("Idempotency-Key"));
	}

	@Test
	@DisplayName("🔴 같은 key + 같은 본문은 저장된 응답을 바이트 그대로 재생한다")
	void sameKeySameBodyReplaysStoredResponse() throws Exception {
		String key = UUID.randomUUID().toString();
		String first = mockMvc.perform(registrationRequest("STU-CHILD1", key))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		String second = mockMvc.perform(registrationRequest("STU-CHILD1", key))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(second).as("재생 응답이 최초와 바이트 단위로 같아야 한다").isEqualTo(first);
		assertThat(countActiveLinks(childProfileId)).as("본 처리가 두 번 돌면 안 된다").isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 같은 key + 다른 본문은 409 IDEMPOTENCY_CONFLICT 이고 행이 늘지 않는다")
	void sameKeyDifferentBodyConflicts() throws Exception {
		String key = UUID.randomUUID().toString();
		mockMvc.perform(registrationRequest("STU-CHILD1", key)).andExpect(status().isCreated());
		long before = countAllActiveLinks();

		mockMvc.perform(registrationRequest("STU-CHILD2", key))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

		assertThat(countAllActiveLinks()).isEqualTo(before);
	}

	// ─────────────────── 초대 검증 · 등록 ───────────────────

	@Test
	@DisplayName("유효한 초대 코드는 200 + 강사 요약이다")
	void studentInvitationVerificationSucceeds() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);

		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, STUDENT_CODE, student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.valid").value(true))
			.andExpect(jsonPath("$.data.teacher.teacherId").value(teacherId.toString()))
			.andExpect(jsonPath("$.data.teacher.displayName").value("김강사"));
	}

	@Test
	@DisplayName("🔴 subject 는 null 이고 academyName 은 필드 자체가 없다 — 지어내지 않는다")
	void teacherSummaryFieldsAreNullWhenColumnAbsent() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);

		MvcResult result = mockMvc.perform(
				codeRequest(STUDENT_INVITE_VERIFICATION, STUDENT_CODE, student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.teacher.subject").doesNotExist())
			.andReturn();

		// 빈 문자열이나 "미지정" 같은 대체 문구가 새지 않았는지 본문 전체로 확인한다.
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("academyName").doesNotContain("미지정");
	}

	@Test
	@DisplayName("만료된 초대는 410 이다")
	void expiredInviteIsGone() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.minusDays(1), null);

		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, STUDENT_CODE, student()))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.error.code").value("INVITE_EXPIRED"));
	}

	@Test
	@DisplayName("폐기된 초대는 410 이다")
	void revokedInviteIsGone() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), now.minusHours(1));

		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, STUDENT_CODE, student()))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.error.code").value("INVITE_EXPIRED"));
	}

	@Test
	@DisplayName("🔴 역할이 다른 초대는 404 다 — 410 도 403 도 아니다(코드 존재를 숨긴다)")
	void roleMismatchedInviteIsNotFound() throws Exception {
		insertInvitation(admin, new InvitationFixture(
			teacherId, "PARENT", PARENT_CODE, now.plusDays(7), null, now));

		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, PARENT_CODE, student()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("없는 코드는 404 다")
	void unknownInviteIsNotFound() throws Exception {
		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, "NO-SUCH-CODE", student()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("학생 초대 등록은 201 + 강사 관계를 만든다")
	void studentClaimCreatesRelationship() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);

		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, STUDENT_CODE,
				UUID.randomUUID().toString(), student()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.teacherId").value(teacherId.toString()));

		assertThat(countTeacherStudentLinks(teacherId, studentProfileId)).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 대기 학생은 초대 검증·등록을 할 수 없다 (MB-02)")
	void studentInvitationBlockedWhilePendingParentLink() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);
		// childProfileId 는 PENDING_PARENT_LINK 그대로다.
		var pending = authentication(principalOf(childAccountId, AccountRole.STUDENT));

		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, STUDENT_CODE, pending))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));

		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, STUDENT_CODE,
				UUID.randomUUID().toString(), pending))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));

		assertThat(countTeacherStudentLinks(teacherId, childProfileId)).isZero();
	}

	@Test
	@DisplayName("🔴 코드 재사용과 관계 중복은 서로 다른 경로다 — 남기는 claim 행 수가 다르다")
	void reusedCodeAndDuplicateRelationshipAreDistinct() throws Exception {
		UUID firstInvitation = insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);
		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, STUDENT_CODE,
				UUID.randomUUID().toString(), student()))
			.andExpect(status().isCreated());

		// ① 같은 코드를 내가 다시 제출 → claim unique 경로. 새 claim 이 생기지 않는다.
		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, STUDENT_CODE,
				UUID.randomUUID().toString(), student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.teacherId").value(teacherId.toString()));
		assertThat(countClaims(firstInvitation)).as("재제출은 claim 을 추가하지 않는다").isEqualTo(1);

		// ② 같은 강사의 **다른** 코드 → claim 은 새로 생기고 관계 unique 경로를 탄다.
		UUID secondInvitation = insertStudentInvitation("STUDENT-CODE-2", now.plusDays(7), null);
		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, "STUDENT-CODE-2",
				UUID.randomUUID().toString(), student()))
			.andExpect(status().isOk());
		assertThat(countClaims(secondInvitation)).as("새 코드는 claim 을 하나 더 남긴다").isEqualTo(1);

		// 어느 경로든 관계는 하나뿐이다 — MB-04 멱등.
		assertThat(countTeacherStudentLinks(teacherId, studentProfileId)).isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 다른 계정이 이미 쓴 코드는 409 INVITE_ALREADY_CLAIMED 다 (MB-38 · V40)")
	void secondAccountCannotClaimSameCode() throws Exception {
		UUID invitationId = insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);

		// 첫 학생이 코드를 소진한다 — pair unique 도 single_use unique 도 방금 채워진다.
		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, STUDENT_CODE,
				UUID.randomUUID().toString(), student()))
			.andExpect(status().isCreated());
		assertThat(countClaims(invitationId)).isEqualTo(1);

		// 두 번째 학생이 같은 코드를 쓰면 uq_member_invitation_claims_single_use 가 막는다.
		// 🔴 RLS 로 남의 claim 이 안 보이는데도 DB 가 판정한다 — 유니크 인덱스가 정책을 우회한다.
		UUID otherAccount = insertAccount(admin, "student2@example.com", "STUDENT", now);
		UUID otherProfile = insertStudent(admin, otherAccount,
			new StudentFixture("김두번", "김두번", 2, "STU-STUD02"), now);
		activate(otherProfile);
		var otherStudent = authentication(principalOf(otherAccount, AccountRole.STUDENT));

		mockMvc.perform(claimRequest(STUDENT_INVITATIONS, STUDENT_CODE,
				UUID.randomUUID().toString(), otherStudent))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVITE_ALREADY_CLAIMED"));

		// 두 번째 학생의 관계는 만들어지지 않았다 — 롤백이 온전하다.
		assertThat(countTeacherStudentLinks(teacherId, otherProfile)).isZero();
		assertThat(countClaims(invitationId))
			.as("두 번째 계정의 claim 이 남으면 single_use 를 뚫은 것이다")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("🔴 평문 초대 코드는 어느 컬럼에도 저장되지 않는다")
	void plaintextCodeIsNeverPersisted() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);

		List<Map<String, Object>> rows =
			admin.queryForList("SELECT * FROM member_invitation_codes");
		assertThat(rows).isNotEmpty();
		for (Map<String, Object> row : rows) {
			assertThat(String.valueOf(row.get("code_hash"))).matches("^sha256:[0-9a-f]{64}$");
			for (Object value : row.values()) {
				assertThat(String.valueOf(value))
					.as("평문 코드가 어느 컬럼에도 남으면 안 된다")
					.doesNotContain(STUDENT_CODE);
			}
		}
	}

	@Test
	@DisplayName("학부모 초대 등록은 201, 같은 강사 재등록은 200 멱등이다 (MB-04)")
	void parentClaimIsIdempotentForSameTeacher() throws Exception {
		insertInvitation(admin, new InvitationFixture(
			teacherId, "PARENT", PARENT_CODE, now.plusDays(7), null, now));

		mockMvc.perform(claimRequest(PARENT_INVITATIONS, PARENT_CODE,
				UUID.randomUUID().toString(), parent()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.teacherId").value(teacherId.toString()));

		insertInvitation(admin, new InvitationFixture(
			teacherId, "PARENT", "PARENT-CODE-2", now.plusDays(7), null, now));
		mockMvc.perform(claimRequest(PARENT_INVITATIONS, "PARENT-CODE-2",
				UUID.randomUUID().toString(), parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.teacherId").value(teacherId.toString()));

		Integer links = admin.queryForObject("SELECT count(*) FROM parent_teacher_relationships"
			+ " WHERE parent_id = ? AND teacher_id = ? AND status = 'ACTIVE'",
			Integer.class, parentProfileId, teacherId);
		assertThat(links).isEqualTo(1);
	}

	@Test
	@DisplayName("학생용 코드를 학부모가 쓰면 404 다")
	void parentCannotUseStudentInvitation() throws Exception {
		insertStudentInvitation(STUDENT_CODE, now.plusDays(7), null);

		mockMvc.perform(codeRequest(PARENT_INVITE_VERIFICATION, STUDENT_CODE, parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("학부모가 학생 경로를 부르면 403 이다 — 역할은 보안 체인이 가른다")
	void parentCannotCallStudentRoute() throws Exception {
		mockMvc.perform(codeRequest(STUDENT_INVITE_VERIFICATION, STUDENT_CODE, parent()))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("인증 없이 부르면 401 이다")
	void anonymousIsUnauthorized() throws Exception {
		mockMvc.perform(get(CHILDREN)).andExpect(status().isUnauthorized());
	}

	// ───────────────────────────── 헬퍼 ─────────────────────────────

	private MockHttpServletRequestBuilder verificationRequest(String publicId) {
		return post(CHILD_VERIFICATION).with(parent())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentPublicId\":\"" + publicId + "\"}");
	}

	private MockHttpServletRequestBuilder registrationRequest(String publicId, String key) {
		return post(CHILDREN).with(parent())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentPublicId\":\"" + publicId + "\"}");
	}

	private MockHttpServletRequestBuilder codeRequest(
		String path, String code,
		org.springframework.test.web.servlet.request.RequestPostProcessor caller
	) {
		return post(path).with(caller)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\"}");
	}

	private MockHttpServletRequestBuilder claimRequest(
		String path, String code, String key,
		org.springframework.test.web.servlet.request.RequestPostProcessor caller
	) {
		return codeRequest(path, code, caller).header("Idempotency-Key", key);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private UUID insertStudentInvitation(
		String code, OffsetDateTime expiresAt, OffsetDateTime revokedAt
	) {
		return insertInvitation(admin, new InvitationFixture(
			teacherId, "STUDENT", code, expiresAt, revokedAt, now));
	}

	private void linkParentToChild(UUID parentId, UUID studentId, String status) {
		admin.update("INSERT INTO parent_student_relationships (id, parent_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(), parentId, studentId, status, now, now);
		admin.update("UPDATE member_student_activation SET status = 'ACTIVE', activated_at = ?"
			+ " WHERE student_id = ? AND status = 'PENDING_PARENT_LINK'", now, studentId);
	}

	private void activate(UUID studentId) {
		admin.update("UPDATE member_student_activation SET status = 'ACTIVE', activated_at = ?"
			+ " WHERE student_id = ?", now, studentId);
	}

	private int countActiveLinks(UUID studentId) {
		Integer count = admin.queryForObject("SELECT count(*) FROM parent_student_relationships"
			+ " WHERE student_id = ? AND status = 'ACTIVE'", Integer.class, studentId);
		return count == null ? 0 : count;
	}

	private long countAllActiveLinks() {
		Long count = admin.queryForObject("SELECT count(*) FROM parent_student_relationships"
			+ " WHERE status = 'ACTIVE'", Long.class);
		return count == null ? 0 : count;
	}

	private int countTeacherStudentLinks(UUID teacher, UUID studentId) {
		Integer count = admin.queryForObject("SELECT count(*) FROM teacher_student_relationships"
			+ " WHERE teacher_id = ? AND student_id = ? AND status = 'ACTIVE'",
			Integer.class, teacher, studentId);
		return count == null ? 0 : count;
	}

	private int countClaims(UUID invitationId) {
		Integer count = admin.queryForObject("SELECT count(*) FROM member_invitation_claims"
			+ " WHERE invitation_id = ?", Integer.class, invitationId);
		return count == null ? 0 : count;
	}
}
