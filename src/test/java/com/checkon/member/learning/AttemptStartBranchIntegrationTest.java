package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import tools.jackson.databind.ObjectMapper;

/** 분기표의 attempt 시작 경쟁·빈 문항·관계·활성화 분기를 제한 역할로 검증한다. */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AttemptStartBranchIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String START =
		"/api/v1/member/students/me/worksheets/{id}/attempts";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private UUID teacherId;
	private UUID studentAccountId;
	private UUID studentId;
	private UUID assignmentId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		clearFixtures(admin);
		now = OffsetDateTime.now();
		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
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
		LearningFixtures.insertGradableItem(
			admin, teacherId, requestId, setId, itemId, 1, 1);
		assignmentId = LearningFixtures.insertAssignment(
			admin, teacherId, requestId, setId, studentId, now);
	}

	@Test
	@DisplayName("start — 동시 두 요청은 201/200과 같은 attemptId 한 건으로 수렴한다")
	void concurrentStartCreatesOne() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var tasks = List.of(
				executor.submit(() -> concurrentStart(ready, start)),
				executor.submit(() -> concurrentStart(ready, start)));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			StartResponse first = tasks.get(0).get(10, TimeUnit.SECONDS);
			StartResponse second = tasks.get(1).get(10, TimeUnit.SECONDS);
			assertThat(List.of(first.status(), second.status()))
				.containsExactlyInAnyOrder(201, 200);
			assertThat(second.attemptId()).isEqualTo(first.attemptId());
		}
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM member_attempts WHERE assignment_id = ?",
			Integer.class, assignmentId)).isEqualTo(1);
	}

	@Test
	@DisplayName("start — 문항 0개 학습지는 422이고 attempt를 만들지 않는다")
	void emptyWorksheetIsNotGradable() throws Exception {
		UUID requestId = LearningFixtures.insertProblemRequest(admin, teacherId, studentId, now);
		UUID setId = LearningFixtures.insertProblemSet(admin, teacherId, requestId, now);
		UUID emptyAssignment = LearningFixtures.insertAssignment(
			admin, teacherId, requestId, setId, studentId, now);

		mockMvc.perform(post(START, emptyAssignment).with(student()))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("WORKSHEET_NOT_GRADABLE"))
			.andExpect(jsonPath("$.error.details.missing[0]").value("items"));
		assertThat(admin.queryForObject(
			"SELECT count(*) FROM member_attempts WHERE assignment_id = ?",
			Integer.class, emptyAssignment)).isZero();
	}

	@Test
	@DisplayName("start — 강사 관계가 ENDED면 권한과 부재를 구분하지 않고 404다")
	void endedTeacherRelationshipIsNotFound() throws Exception {
		admin.update("UPDATE teacher_student_relationships SET status='ENDED', ended_at=?"
			+ " WHERE teacher_id=? AND student_id=?", now, teacherId, studentId);
		mockMvc.perform(post(START, assignmentId).with(student()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("start — 대기 학생은 403 STUDENT_ACTIVATION_REQUIRED다")
	void pendingStudentIsForbidden() throws Exception {
		admin.update("UPDATE member_student_activation SET status='PENDING_PARENT_LINK',"
			+ " activated_at=NULL, updated_at=? WHERE student_id=?", now, studentId);
		mockMvc.perform(post(START, assignmentId).with(student()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("STUDENT_ACTIVATION_REQUIRED"));
	}

	private StartResponse concurrentStart(CountDownLatch ready, CountDownLatch start)
		throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("concurrent start latch timed out");
		}
		var response = mockMvc.perform(post(START, assignmentId).with(student()))
			.andReturn().getResponse();
		String attemptId = objectMapper.readTree(response.getContentAsString())
			.get("data").get("attemptId").asText();
		return new StartResponse(response.getStatus(), attemptId);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private record StartResponse(int status, String attemptId) {
	}
}
