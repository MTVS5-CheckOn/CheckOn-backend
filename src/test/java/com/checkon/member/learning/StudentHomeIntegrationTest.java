package com.checkon.member.learning;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

/** 학생 홈의 continuing/today 빈 상태와 진행 중 상태를 wire에서 검증한다. */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class StudentHomeIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String HOME = "/api/v1/member/students/me/home";
	private static final String START =
		"/api/v1/member/students/me/worksheets/{id}/attempts";

	@Autowired MockMvc mockMvc;

	private JdbcTemplate admin;
	private UUID studentAccountId;
	private UUID studentId;
	private UUID assignmentId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now();
		UUID teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccountId, "박학생", null, now);
		LearningFixtures.activateStudent(admin, studentId, now);
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);
		UUID requestId = LearningFixtures.insertProblemRequest(admin, teacherId, studentId, now);
		UUID setId = LearningFixtures.insertProblemSet(admin, teacherId, requestId, now);
		UUID itemId = LearningFixtures.insertProblemItem(admin, teacherId, requestId, 1, now);
		LearningFixtures.insertGradableItem(admin, teacherId, requestId, setId, itemId, 1, 1);
		assignmentId = LearningFixtures.insertAssignment(
			admin, teacherId, requestId, setId, studentId, now);
	}

	@Test
	@DisplayName("home — 진행 중 attempt가 없으면 continuing:null이고 오늘 학습지는 보인다")
	void noContinuingIsNull() throws Exception {
		mockMvc.perform(get(HOME).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.studentName").value("박학생"))
			.andExpect(jsonPath("$.data.continuing").value((Object) null))
			.andExpect(jsonPath("$.data.todayWorksheets.length()").value(1))
			.andExpect(jsonPath("$.data.weakness.status").value("NO_DATA"))
			.andExpect(jsonPath("$.data.weakness.accuracyRate").value((Object) null));
	}

	@Test
	@DisplayName("home — 오늘 학습지가 없으면 todayWorksheets:[]다")
	void noTodayWorksheetsIsEmpty() throws Exception {
		admin.update("UPDATE problem_assignments SET published_at = published_at - interval '2 days'"
			+ " WHERE id = ?", assignmentId);
		mockMvc.perform(get(HOME).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.todayWorksheets.length()").value(0));
	}

	@Test
	@DisplayName("home — 열린 attempt가 있으면 continuing에 같은 attemptId가 나온다")
	void continuingShowsOpenAttempt() throws Exception {
		String body = mockMvc.perform(post(START, assignmentId).with(student()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String attemptId = body.split("\\\"attemptId\\\":\\\"")[1].split("\\\"")[0];
		mockMvc.perform(get(HOME).with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.continuing.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data.continuing.latestAttemptId").value(attemptId));
	}

	@Test
	@DisplayName("home — 대기 학생은 403 STUDENT_ACTIVATION_REQUIRED다")
	void pendingStudentIsForbidden() throws Exception {
		admin.update("UPDATE member_student_activation SET status='PENDING_PARENT_LINK',"
			+ " activated_at=NULL, updated_at=? WHERE student_id=?",
			OffsetDateTime.now(), studentId);
		mockMvc.perform(get(HOME).with(student()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}
}
