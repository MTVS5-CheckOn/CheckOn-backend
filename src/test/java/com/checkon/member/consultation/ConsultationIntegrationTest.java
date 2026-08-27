package com.checkon.member.consultation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ConsultationIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String CREATE = "/api/v1/member/parents/me/consultations";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;
	@Autowired ApplicationContext applicationContext;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID otherParentAccountId;
	private UUID otherParentProfileId;
	private UUID studentProfileId;
	private UUID otherStudentProfileId;
	private UUID teacherId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now(ZoneOffset.UTC);
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "김부모", now);
		otherParentAccountId = insertAccount(admin, "other-parent@example.com", "PARENT", now);
		otherParentProfileId = insertParent(admin, otherParentAccountId, "이부모", now);

		UUID studentAccount = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccount, "김학생", 2, now);
		UUID otherStudentAccount = insertAccount(
			admin, "other-student@example.com", "STUDENT", now);
		otherStudentProfileId = MemberPostgresSupport.insertStudentProfile(
			admin, otherStudentAccount, "이학생", 3, now);
		teacherId = insertTeacher(admin, "teacher@example.com", "박강사", now);

		linkParent(parentProfileId, studentProfileId);
		linkParent(otherParentProfileId, otherStudentProfileId);
		linkTeacher(studentProfileId, "ACTIVE");
		linkTeacher(otherStudentProfileId, "ACTIVE");
	}

	@Test
	void 정상_요청은_AI호출_없이_원문과_마스킹본을_저장하고_201을_낸다() throws Exception {
		String body = body("전화 010-1234-5678, mail@example.com");
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.studentId").value(studentProfileId.toString()))
			.andExpect(jsonPath("$.data.teacherId").value(teacherId.toString()))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.data.aiAssistance").value("NOT_REQUESTED"));

		String[] stored = admin.queryForObject(
			"SELECT content, masked_content FROM member_consultations",
			(rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)});
		assertThat(stored[0]).contains("010-1234-5678", "mail@example.com");
		assertThat(stored[1]).doesNotContain("010-1234-5678", "mail@example.com");
		assertThat(applicationContext.getBeanNamesForType(java.net.http.HttpClient.class)).isEmpty();
	}

	@Test
	void 내용_길이_경계는_400이고_저장하지_않는다() throws Exception {
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body("")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body("가".repeat(2001))))
			.andExpect(status().isBadRequest());
		assertThat(countConsultations()).isZero();
	}

	@Test
	void 연결되지_않은_자녀는_404다() throws Exception {
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(bodyFor(otherStudentProfileId, teacherId, "상담")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void 학생_강사_관계가_끝났으면_422다() throws Exception {
		admin.update("UPDATE teacher_student_relationships SET status='ENDED', ended_at=?"
			+ " WHERE teacher_id=? AND student_id=?", now, teacherId, studentProfileId);
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body("상담")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("RELATIONSHIP_REQUIRED"));
	}

	@Test
	void 존재하지_않는_context는_404다() throws Exception {
		String body = "{\"studentId\":\"" + studentProfileId + "\","
			+ "\"teacherId\":\"" + teacherId + "\",\"content\":\"상담\","
			+ "\"context\":{\"type\":\"RECORD\",\"id\":\"" + UUID.randomUUID()
			+ "\"}}";
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isNotFound());
	}

	@Test
	void 같은_멱등키와_같은_body는_같은_응답을_재생한다() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = body("상담");
		String first = mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String replay = mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertThat(replay).isEqualTo(first);
		assertThat(countConsultations()).isEqualTo(1);
	}

	@Test
	void 같은_멱등키와_다른_body는_409다() throws Exception {
		String key = UUID.randomUUID().toString();
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body("상담 A")))
			.andExpect(status().isCreated());
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body("상담 B")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test
	void 멱등키가_없으면_400이다() throws Exception {
		mockMvc.perform(post(CREATE).with(parent())
				.contentType(MediaType.APPLICATION_JSON).content(body("상담")))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 상담이_없으면_목록은_빈_배열이다() throws Exception {
		mockMvc.perform(get(childPath()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void 목록은_상담의_실제_값을_돌려준다() throws Exception {
		UUID id = createConsultation();
		mockMvc.perform(get(childPath()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].consultationId").value(id.toString()))
			.andExpect(jsonPath("$.data.items[0].status").value("SUBMITTED"))
			.andExpect(jsonPath("$.data.items[0].aiAssistance").value("NOT_REQUESTED"));
	}

	@Test
	void 답변_전_상세는_원문과_빈_messages와_answeredAt_null을_돌려준다() throws Exception {
		UUID id = createConsultation();
		String response = mockMvc.perform(get(detailPath(id)).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").value("상담"))
			.andExpect(jsonPath("$.data.messages").isEmpty())
			.andReturn().getResponse().getContentAsString();
		assertThat(response).contains("\"answeredAt\":null");
	}

	@Test
	void 발행된_메시지는_상세에_실제_값으로_노출된다() throws Exception {
		UUID id = createConsultation();
		UUID messageId = UUID.randomUUID();
		admin.update("INSERT INTO member_consultation_messages"
			+ " (id, consultation_id, parent_id, student_id, teacher_id, author_role,"
			+ " content, published_at, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'TEACHER', '확인했습니다', ?, ?)",
			messageId, id, parentProfileId, studentProfileId, teacherId, now, now);
		mockMvc.perform(get(detailPath(id)).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].messageId").value(messageId.toString()))
			.andExpect(jsonPath("$.data.messages[0].authorRole").value("TEACHER"))
			.andExpect(jsonPath("$.data.messages[0].content").value("확인했습니다"));
	}

	@Test
	void 상세_원문_body에는_AI초안_키가_없다() throws Exception {
		UUID id = createConsultation();
		String response = mockMvc.perform(get(detailPath(id)).with(parent()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(response)
			.doesNotContain("\"aiDraft\"", "\"draft\"", "\"evidence\"");
	}

	@Test
	void 남의_상담은_404다() throws Exception {
		UUID foreign = insertConsultation(
			otherParentProfileId, otherStudentProfileId, "남의 상담");
		mockMvc.perform(get(detailPath(foreign)).with(parent()))
			.andExpect(status().isNotFound());
	}

	@Test
	void 목록_limit과_cursor가_잘못되면_400이다() throws Exception {
		mockMvc.perform(get(childPath() + "?limit=51").with(parent()))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get(childPath() + "?cursor=not-a-cursor").with(parent()))
			.andExpect(status().isBadRequest());
	}

	private UUID createConsultation() throws Exception {
		mockMvc.perform(post(CREATE).with(parent())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body("상담")))
			.andExpect(status().isCreated());
		return admin.queryForObject(
			"SELECT id FROM member_consultations WHERE parent_id=?", UUID.class, parentProfileId);
	}

	private UUID insertConsultation(UUID parentId, UUID studentId, String content) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO member_consultations"
			+ " (id, parent_id, student_id, teacher_id, content, masked_content, status,"
			+ " ai_status, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED', 'NOT_REQUESTED', ?, ?)",
			id, parentId, studentId, teacherId, content, content, now, now);
		return id;
	}

	private void linkParent(UUID parentId, UUID studentId) {
		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentId, studentId, now, now);
	}

	private void linkTeacher(UUID studentId, String status) {
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(), teacherId, studentId, status, now, now);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	private String body(String content) {
		return bodyFor(studentProfileId, teacherId, content);
	}

	private static String bodyFor(UUID studentId, UUID teacherId, String content) {
		return "{\"studentId\":\"" + studentId + "\",\"teacherId\":\"" + teacherId
			+ "\",\"content\":\"" + content + "\"}";
	}

	private String childPath() {
		return "/api/v1/member/parents/me/children/" + studentProfileId + "/consultations";
	}

	private String detailPath(UUID consultationId) {
		return childPath() + "/" + consultationId;
	}

	private int countConsultations() {
		Integer count = admin.queryForObject(
			"SELECT count(*) FROM member_consultations", Integer.class);
		return count == null ? 0 : count;
	}
}
