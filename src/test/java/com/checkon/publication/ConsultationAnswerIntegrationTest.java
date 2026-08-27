package com.checkon.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

/**
 * 강사 상담 답변 발행.
 *
 * <p>🔴 <b>끝에서 끝까지 한 번</b> — 학부모가 <b>실제 접수 API 로</b> 상담을 넣고, 강사가
 * 답을 발행하고, <b>학부모 컨텍스트 API 가 그 답을 돌려주는지</b>까지 본다.
 * 쓰기만 확인하면 반쪽이다: PR8 이 만든 것은 접수·조회뿐이었고 이 작업의 존재 이유가
 * 「답이 학부모에게 닿는가」다.</p>
 *
 * <p>🔴 <b>RLS 가 실제로 걸리는 역할</b> 위에서 돈다({@link MembershipRlsEnforcedSupport}).
 * 컨테이너 기본 사용자는 {@code super=true bypassrls=true} 라 정책이 통째로 우회되고(MB-34),
 * 그 위에서는 「남의 상담에 답할 수 없다」가 무엇을 재는지 알 수 없다. 전제 단언 둘을 둔다 —
 * ① 앱 역할이 {@code false/false} ② 막힌 행이 <b>실제로 존재</b>한다.</p>
 */
@SpringBootTest(properties = {
	// 🔴 member 통합 테스트는 이 값을 true 로 두지만 여기서는 false 다 — 이유가 있다.
	//    dev 프로파일의 DevelopmentTestAuthenticationFilter 는 Authorization 헤더가 없는
	//    요청에 고정 강사 인증을 심는다. MemberSecurityConfiguration 은 그 필터를 체인에
	//    넣지 않아 member 경로가 영향을 안 받지만, 이 경계는 AccountSecurityConfiguration
	//    체인 위에 있어 그대로 받는다(실측: true 로 두면 인증 없는 요청이 401 이 아니라
	//    200 이었다). true 로 두면 「인증이 필요하다」를 재는 단언이 헛돈다 → MB-67.
	"checkon.security.test-authentication.enabled=false",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ConsultationAnswerIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String TEACHER_LIST = "/api/v1/member-consultations";
	private static final String PARENT_CREATE = "/api/v1/member/parents/me/consultations";

	private static final String QUESTION = "아이가 분수 단원을 어려워합니다";
	private static final String ANSWER = "다음 주 보강에서 그 단원을 다시 다루겠습니다";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private OffsetDateTime now;

	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID studentProfileId;
	private UUID teacherAccountId;
	private UUID teacherId;
	private UUID otherTeacherAccountId;
	private UUID otherTeacherId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now(ZoneOffset.UTC);

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "김부모", now);
		UUID studentAccount = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccount, "김학생", 2, now);

		teacherId = insertTeacher(admin, "teacher@example.com", "박강사", now);
		teacherAccountId = accountOf(teacherId);
		otherTeacherId = insertTeacher(admin, "other-teacher@example.com", "홍강사", now);
		otherTeacherAccountId = accountOf(otherTeacherId);

		linkParent(parentProfileId, studentProfileId);
		linkTeacher(teacherId, studentProfileId);
		linkTeacher(otherTeacherId, studentProfileId);
	}

	// ══════════════════════ 전제 · 보안 체인 ══════════════════════

	@Test
	void 전제_앱_역할은_RLS_대상이다() {
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");
	}

	/**
	 * 🔴 <b>어느 체인이 이 경로를 잡는지 실제 요청으로 확인한다.</b> 코드를 읽어 추론한 것과
	 * 도는 것은 다르다.
	 *
	 * <p>학부모가 <b>403</b> 을 받는다는 사실 자체가 증거다 —
	 * {@code AccountSecurityConfiguration} 의 {@code /api/v1/** → hasRole("TEACHER")} 가
	 * 잡았다는 뜻이다. member 체인({@code securityMatcher("/api/v1/member/**")})이 잡았다면
	 * 그 체인은 역할을 가리지 않으므로 학부모가 <b>통과</b>해 컨트롤러까지 갔을 것이다.</p>
	 */
	@Test
	void 새_경로는_승우님_TEACHER_체인이_잡는다_학부모는_403이다() throws Exception {
		mockMvc.perform(get(TEACHER_LIST).with(teacher()))
			.andExpect(status().isOk());
		mockMvc.perform(get(TEACHER_LIST).with(parent()))
			.andExpect(status().isForbidden());
	}

	@Test
	void 인증_없이_부르면_401이다() throws Exception {
		mockMvc.perform(get(TEACHER_LIST)).andExpect(status().isUnauthorized());
	}

	// ══════════════════════ 끝에서 끝까지 ══════════════════════

	/** 🔴 이 작업의 존재 이유다. 학부모 접수 → 강사 발행 → <b>학부모가 답을 본다</b>. */
	@Test
	void 끝에서_끝까지_강사가_발행한_답을_학부모_API가_돌려준다() throws Exception {
		UUID consultationId = parentSubmits();

		mockMvc.perform(get(parentDetail(consultationId)).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.data.messages").isEmpty());

		mockMvc.perform(get(TEACHER_LIST).with(teacher()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].consultationId").value(consultationId.toString()))
			.andExpect(jsonPath("$[0].answerable").value(true));

		mockMvc.perform(get(teacherDetail(consultationId)).with(teacher()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value(QUESTION))
			.andExpect(jsonPath("$.answerable").value(true));

		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.consultationId").value(consultationId.toString()))
			.andExpect(jsonPath("$.status").value("ANSWERED"))
			.andExpect(jsonPath("$.publishedAt").exists());

		mockMvc.perform(get(parentDetail(consultationId)).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ANSWERED"))
			.andExpect(jsonPath("$.data.answeredAt").exists())
			.andExpect(jsonPath("$.data.messages[0].authorRole").value("TEACHER"))
			.andExpect(jsonPath("$.data.messages[0].content").value(ANSWER));
	}

	/**
	 * 🔴 발행 시각은 <b>한 번만</b> 뜬다 — 메시지 {@code published_at} 과 상담
	 * {@code answered_at} 이 같은 값이어야 한다. 두 번 뜨면 「같은 발행인데 시각이 갈리는」
	 * 상태가 만들어지고, 학부모 화면의 정렬·표시가 그 차이만큼 어긋난다.
	 */
	@Test
	void 발행_시각은_메시지와_상담이_같다() throws Exception {
		UUID consultationId = parentSubmits();
		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isCreated());

		OffsetDateTime publishedAt = admin.queryForObject(
			"SELECT published_at FROM member_consultation_messages WHERE consultation_id = ?",
			OffsetDateTime.class, consultationId);
		OffsetDateTime answeredAt = admin.queryForObject(
			"SELECT answered_at FROM member_consultations WHERE id = ?",
			OffsetDateTime.class, consultationId);
		assertThat(answeredAt).isEqualTo(publishedAt);
	}

	@Test
	void 상세_응답에_AI_초안_본문_필드가_없다() throws Exception {
		UUID consultationId = parentSubmits();
		String response = mockMvc.perform(get(teacherDetail(consultationId)).with(teacher()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiAssistance").value("NOT_REQUESTED"))
			.andReturn().getResponse().getContentAsString();
		// 🔴 doesNotExist() 는 값이 null 이어도 통과한다 — 원문 문자열로 본다.
		assertThat(response).doesNotContain("\"aiDraft\"", "\"draft\"", "\"evidence\"");
	}

	@Test
	void 목록에는_학부모_원문이_없다() throws Exception {
		parentSubmits();
		String response = mockMvc.perform(get(TEACHER_LIST).with(teacher()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain(QUESTION);
	}

	// ══════════════════════ 격리 ══════════════════════

	/** 🔴 남의 상담에는 답할 수 없다. 막는 것은 WHERE 절이 아니라 V44 정책이다. */
	@Test
	void 남의_상담에는_답할_수_없고_404다() throws Exception {
		UUID consultationId = parentSubmits();

		mockMvc.perform(answerRequest(consultationId, otherTeacher(), ANSWER))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		// 🔴 전제 ② — 그 상담 행은 실제로 존재한다. 없어서 404 인 것이 아니다.
		assertThat(admin.queryForObject("SELECT count(*) FROM member_consultations WHERE id = ?",
			Integer.class, consultationId)).isEqualTo(1);
		assertThat(countMessages()).as("남의 상담에 메시지가 들어갔다").isZero();
		assertThat(statusOf(consultationId)).isEqualTo("SUBMITTED");
	}

	@Test
	void 남의_상담은_목록에도_상세에도_없다() throws Exception {
		UUID consultationId = parentSubmits();
		mockMvc.perform(get(TEACHER_LIST).with(otherTeacher()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
		mockMvc.perform(get(teacherDetail(consultationId)).with(otherTeacher()))
			.andExpect(status().isNotFound());
	}

	// ══════════════════════ 멱등 · 상태 ══════════════════════

	/**
	 * 🔴 멱등을 <b>상태로</b> 본다. 두 번째 호출은 잠그고 읽어 {@code ANSWERED} 를 보고 409 다 —
	 * 답이 두 벌 나가지 않는다. ⚠ 타임아웃 재시도 클라이언트도 409 를 받는다(MB-65).
	 */
	@Test
	void 두_번_부르면_두_번째는_409이고_메시지는_한_벌이다() throws Exception {
		UUID consultationId = parentSubmits();
		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isCreated());
		mockMvc.perform(answerRequest(consultationId, teacher(), "두 번째 답"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));

		assertThat(countMessages()).isEqualTo(1);
		assertThat(admin.queryForObject(
			"SELECT content FROM member_consultation_messages", String.class))
			.isEqualTo(ANSWER);
		assertThat(statusOf(consultationId)).isEqualTo("ANSWERED");
	}

	/** 🔴 취소된 상담에는 답하지 않는다. 학부모가 물러난 뒤에 답이 도착하면 안 된다. */
	@Test
	void 취소된_상담에는_답할_수_없다() throws Exception {
		UUID consultationId = parentSubmits();
		// 🔴 시각을 now() 로 뜬다. setUp 의 now 는 API 가 만든 행의 created_at 보다 과거라
		//    ck_member_consultations_updated_at (updated_at >= created_at) 을 위반한다.
		admin.update("UPDATE member_consultations SET status='CANCELLED', cancelled_at=now(),"
			+ " updated_at=now() WHERE id=?", consultationId);

		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
		assertThat(countMessages()).isZero();
	}

	@Test
	void 종료된_상담에는_답할_수_없다() throws Exception {
		UUID consultationId = parentSubmits();
		admin.update("UPDATE member_consultations SET status='CLOSED', updated_at=now()"
			+ " WHERE id=?", consultationId);

		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isConflict());
		assertThat(countMessages()).isZero();
	}

	/** 🔴 {@code REVIEWING} 은 답할 수 있다 — 「검토 중」은 아직 답이 안 나간 상태다. */
	@Test
	void 검토중_상담에는_답할_수_있다() throws Exception {
		UUID consultationId = parentSubmits();
		admin.update("UPDATE member_consultations SET status='REVIEWING', updated_at=now()"
			+ " WHERE id=?", consultationId);

		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isCreated());
		assertThat(statusOf(consultationId)).isEqualTo("ANSWERED");
	}

	// ══════════════════════ 입력 검증 ══════════════════════

	@Test
	void 빈_본문과_상한_초과는_400이고_저장하지_않는다() throws Exception {
		UUID consultationId = parentSubmits();
		mockMvc.perform(answerRequest(consultationId, teacher(), "  "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(answerRequest(consultationId, teacher(), "가".repeat(2001)))
			.andExpect(status().isBadRequest());
		assertThat(countMessages()).isZero();
		assertThat(statusOf(consultationId)).isEqualTo("SUBMITTED");
	}

	/** 🔴 읽을 수 없는 본문은 500 이 아니라 400 이다(member G18 이 막은 것과 같은 구멍). */
	@Test
	void 읽을_수_없는_본문은_400이다() throws Exception {
		UUID consultationId = parentSubmits();
		mockMvc.perform(post(teacherAnswer(consultationId)).with(teacher())
				.contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void 목록_파라미터가_잘못되면_400이다() throws Exception {
		mockMvc.perform(get(TEACHER_LIST).param("limit", "51").with(teacher()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(get(TEACHER_LIST).param("limit", "abc").with(teacher()))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get(TEACHER_LIST).param("status", "PENDING").with(teacher()))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get(TEACHER_LIST).param("cursor", "not-a-cursor").with(teacher()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 목록_status_필터는_실제로_좁힌다() throws Exception {
		UUID answered = parentSubmits();
		mockMvc.perform(answerRequest(answered, teacher(), ANSWER))
			.andExpect(status().isCreated());
		UUID open = parentSubmits();

		mockMvc.perform(get(TEACHER_LIST).with(teacher()))
			.andExpect(jsonPath("$.length()").value(2));
		mockMvc.perform(get(TEACHER_LIST).param("status", "SUBMITTED").with(teacher()))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].consultationId").value(open.toString()));
	}

	// ══════════════════════ 알림 (MB-66) ══════════════════════

	/**
	 * 🔴 <b>알림을 만들지 않는다</b> — {@code member_notifications} INSERT 정책이
	 * {@code current_checkon_account_id() IS NOT NULL} 을 요구하는데 강사 컨텍스트에서 그
	 * 값은 NULL 이라 RLS 가 거절한다. 계정 컨텍스트를 열면 게이트 위반이다(MB-66).
	 * 「지금은 안 만든다」를 사실로 박아 둔다 — 나중에 누가 조용히 뚫으면 여기서 걸린다.
	 */
	@Test
	void 발행은_학부모_알림을_만들지_않는다_MB66() throws Exception {
		UUID consultationId = parentSubmits();
		mockMvc.perform(answerRequest(consultationId, teacher(), ANSWER))
			.andExpect(status().isCreated());
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM member_notifications", Integer.class)).isZero();
	}

	// ──────────────────────────── 도우미 ────────────────────────────

	/** 🔴 학부모가 <b>실제 접수 API 로</b> 넣는다. 픽스처로 넣으면 끝에서 끝까지가 아니다. */
	private UUID parentSubmits() throws Exception {
		String body = "{\"studentId\":\"" + studentProfileId + "\",\"teacherId\":\""
			+ teacherId + "\",\"content\":\"" + QUESTION + "\"}";
		mockMvc.perform(post(PARENT_CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
		return admin.queryForObject(
			"SELECT id FROM member_consultations ORDER BY created_at DESC, id DESC LIMIT 1",
			UUID.class);
	}

	private static MockHttpServletRequestBuilder answerRequest(
		UUID consultationId, RequestPostProcessor who, String content
	) {
		return post(teacherAnswer(consultationId)).with(who)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + content + "\"}");
	}

	private static String teacherDetail(UUID consultationId) {
		return TEACHER_LIST + "/" + consultationId;
	}

	private static String teacherAnswer(UUID consultationId) {
		return teacherDetail(consultationId) + "/answer";
	}

	private String parentDetail(UUID consultationId) {
		return "/api/v1/member/parents/me/children/" + studentProfileId + "/consultations/"
			+ consultationId;
	}

	private String statusOf(UUID consultationId) {
		return admin.queryForObject("SELECT status FROM member_consultations WHERE id = ?",
			String.class, consultationId);
	}

	private int countMessages() {
		Integer count = admin.queryForObject(
			"SELECT count(*) FROM member_consultation_messages", Integer.class);
		return count == null ? 0 : count;
	}

	private UUID accountOf(UUID teacherProfileId) {
		return admin.queryForObject("SELECT account_id FROM teacher_profiles WHERE id = ?",
			UUID.class, teacherProfileId);
	}

	private void linkParent(UUID parentId, UUID studentId) {
		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentId, studentId, now, now);
	}

	private void linkTeacher(UUID teacherProfileId, UUID studentId) {
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherProfileId, studentId, now, now);
	}

	private RequestPostProcessor teacher() {
		return authentication(teacherPrincipal(teacherAccountId, teacherId));
	}

	private RequestPostProcessor otherTeacher() {
		return authentication(teacherPrincipal(otherTeacherAccountId, otherTeacherId));
	}

	private RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	/**
	 * 🔴 강사는 {@code teacherProfileId} 가 <b>채워진</b> principal 이다.
	 * {@link MembershipRlsEnforcedSupport#principalOf} 는 그 자리를 {@code null} 로 두므로
	 * (학생·학부모용이다) 여기서 직접 만든다 — {@code null} 로 조회하면 예외가 아니라
	 * 조용히 0행이고, 그러면 이 클래스의 단언이 전부 헛돈다.
	 */
	private static Authentication teacherPrincipal(UUID accountId, UUID teacherProfileId) {
		AuthenticatedAccount account = new AuthenticatedAccount(
			accountId, AccountRole.TEACHER, teacherProfileId, UUID.randomUUID());
		return new UsernamePasswordAuthenticationToken(account, null,
			List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
	}
}
