package com.checkon.dashboard.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(DashboardControllerIntegrationTest.FixedClockConfiguration.class)
class DashboardControllerIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f000-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198f000-0000-7000-8000-000000000002");
	private static final UUID STUDENT = UUID.fromString("0198f000-0000-7000-8000-000000000003");
	private static final UUID OTHER_STUDENT = UUID.fromString("0198f000-0000-7000-8000-000000000004");
	private static final UUID CLASS = UUID.fromString("0198f000-0000-7000-8000-000000000005");
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);
	private static final LocalDate WEEK_START = TODAY.with(
		TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)
	);
	private static final LocalDate WEEK_END = WEEK_START.plusDays(6);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock dashboardTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM alert_follow_up_todos");
		jdbc.update("DELETE FROM intervention_reminders");
		jdbc.update("DELETE FROM interventions");
		jdbc.update("DELETE FROM engagement_alerts");
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM student_personal_information");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		insertStudent(STUDENT, "실명처럼 보이는 별칭", "st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", TEACHER);
		insertStudent(OTHER_STUDENT, "다른 강사 학생", "st_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", OTHER_TEACHER);
		insertRelationship(TEACHER, STUDENT);
		insertRelationship(OTHER_TEACHER, OTHER_STUDENT);
		jdbc.update("""
			INSERT INTO class_groups(id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, '고1 수능 국어반', '국어', 'ACTIVE', ?, ?)
			""", CLASS, TEACHER, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
		insertRealName(TEACHER, STUDENT, "김서연");
		insertRunWithAlerts(TEACHER, STUDENT, TODAY, "today", "st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		insertRunWithAlerts(OTHER_TEACHER, OTHER_STUDENT, TODAY, "other", "st_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		insertRunWithAlerts(TEACHER, STUDENT, TODAY.minusDays(2), "past", "st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		UUID todoAlert = jdbc.queryForObject("""
			SELECT alert.id FROM engagement_alerts alert
			JOIN detection_signal_results signal ON signal.id = alert.detection_signal_result_id
			JOIN detection_runs run ON run.id = signal.detection_run_id
			WHERE alert.teacher_id = ? AND run.analysis_date = ? AND signal.rule_id = 'R1'
			""", UUID.class, TEACHER, TODAY);
		jdbc.update("""
			INSERT INTO alert_follow_up_todos(
			 teacher_id, kind, alert_id, text, due_date, status, created_at, updated_at
			) VALUES (?, 'ALERT_FOLLOW_UP', ?, '확인이 필요한 경보를 검토해 주세요.', ?, 'OPEN', ?, ?)
			""", TEACHER, todoAlert, TODAY, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
	}

	@Test
	void teacherCanQueryTodayWithOrderedSignalsEvidenceAndActualStatuses() throws Exception {
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.date").value(TODAY.toString()))
			.andExpect(jsonPath("$.alerts.length()").value(3))
			.andExpect(jsonPath("$.alerts[0].rank").value(1))
			.andExpect(jsonPath("$.alerts[1].rank").value(2))
			.andExpect(jsonPath("$.alerts[2].rank").value(3))
			.andExpect(jsonPath("$.alerts[0].ruleId").value("R1"))
			.andExpect(jsonPath("$.alerts[0].signalType").value("hidden_risk"))
			.andExpect(jsonPath("$.alerts[0].displayLabel").value("위험"))
			.andExpect(jsonPath("$.alerts[0].className").value("고1 수능 국어반"))
			.andExpect(jsonPath("$.alerts[0].createdAt").value("2026-08-05T00:00:00Z"))
			.andExpect(jsonPath("$.alerts[0].brief").value("브리핑 today 1"))
			.andExpect(jsonPath("$.alerts[0].briefFallback").value(false))
			.andExpect(jsonPath("$.alerts[0].evidence[0].recordId").value("record-today-1"))
			.andExpect(jsonPath("$.alerts[0].evidence[0].summary").value("근거 today 1"))
			.andExpect(jsonPath("$.alerts[0].status").value("PENDING_REVIEW"))
			.andExpect(jsonPath("$.alerts[1].status").value("APPROVED"))
			.andExpect(jsonPath("$.alerts[2].status").value("REJECTED"))
			.andExpect(jsonPath("$.alerts[0].studentName").value("김서연"))
			.andExpect(jsonPath("$.todos[0].displayLabel").value("위험"))
			.andExpect(jsonPath("$.todos[0].createdAt").value("2026-08-05T00:00:00Z"))
			.andExpect(jsonPath("$.alerts[0].feedbackGiven").doesNotExist());
	}

	@Test
	void pastAndEmptyDatesReturnNormally() throws Exception {
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.minusDays(2).toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.alerts.length()").value(3));
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.minusDays(1).toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.alerts").isEmpty())
			.andExpect(jsonPath("$.reminders").isArray());
	}

	@Test
	void remindersAreGroupedByAlertAndUseTheLatestValidIntervention() throws Exception {
		UUID alertId = jdbc.queryForObject("""
			SELECT id FROM engagement_alerts
			WHERE teacher_id = ? AND status = 'APPROVED'
			ORDER BY id LIMIT 1
			""", UUID.class, TEACHER);
		UUID older = UUID.fromString("0198f100-0000-7000-8000-000000000001");
		UUID latest = UUID.fromString("0198f100-0000-7000-8000-000000000002");
		Instant olderAt = TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().minusSeconds(3600);
		Instant latestAt = olderAt.plusSeconds(60);
		insertInterventionWithReminder(older, alertId, olderAt, olderAt.plusSeconds(120));
		insertInterventionWithReminder(latest, alertId, latestAt, latestAt.plusSeconds(120));

		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reminders.length()").value(1))
			.andExpect(jsonPath("$.reminders[0].alertId").value(alertId.toString()))
			.andExpect(jsonPath("$.reminders[0].interventionCount").value(2))
			.andExpect(jsonPath("$.reminders[0].latestIntervention.interventionId")
				.value(latest.toString()))
			.andExpect(jsonPath("$.reminders[0].latestIntervention.summary")
				.value("개입 후 재확인이 예정되어 있습니다."))
			.andExpect(jsonPath("$.reminders[0].latestIntervention.content").doesNotExist())
			.andExpect(jsonPath("$.reminders[0].studentName").value("김서연"))
			.andExpect(jsonPath("$.reminders[0].status").doesNotExist());
	}

	@Test
	void invalidDatesAreRejected() throws Exception {
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.plusDays(1).toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FUTURE_DATE_NOT_ALLOWED"))
			.andExpect(jsonPath("$.message").value("Future briefing dates cannot be queried."));
		mvc.perform(get("/api/v1/dashboard/briefing").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", "2026/08/05").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void missingRealNameIsNullAndNeverFallsBackToEitherAlias() throws Exception {
		jdbc.update("DELETE FROM student_personal_information WHERE student_id = ?", STUDENT);
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.alerts[0].studentName")
				.value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void tenantComesOnlyFromPrincipalAndEndpointRequiresTeacherAuthentication() throws Exception {
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.toString())
				.param("teacherId", OTHER_TEACHER.toString())
				.param("tenantId", OTHER_TEACHER.toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.alerts.length()").value(3))
			.andExpect(jsonPath("$.alerts[0].studentId").value(STUDENT.toString()));
		mvc.perform(get("/api/v1/dashboard/briefing").param("date", TODAY.toString()))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/dashboard/briefing").param("date", TODAY.toString())
				.with(parentAuthentication()))
			.andExpect(status().isForbidden());
	}

	@Test
	void teacherCanQuerySevenOrderedDaysIncludingEmptyDatesAndAllAlertStatuses() throws Exception {
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_END.toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startedAt").value(WEEK_START.toString()))
			.andExpect(jsonPath("$.endedAt").value(WEEK_END.toString()))
			.andExpect(jsonPath("$.items.length()").value(7))
			.andExpect(jsonPath("$.items[0].date").value(WEEK_START.toString()))
			.andExpect(jsonPath("$.items[0].eventCount").value(3))
			.andExpect(jsonPath("$.items[1].eventCount").value(0))
			.andExpect(jsonPath("$.items[2].date").value(TODAY.toString()))
			.andExpect(jsonPath("$.items[2].eventCount").value(3))
			.andExpect(jsonPath("$.items[6].date").value(WEEK_END.toString()))
			.andExpect(jsonPath("$.items[6].eventCount").value(0));
	}

	@Test
	void calendarUsesAnalysisDateEvenWhenAlertInstantFallsOnAnotherUtcDate() throws Exception {
		jdbc.update("""
			UPDATE engagement_alerts
			SET created_at = TIMESTAMPTZ '2026-08-02 15:30:00+00',
			    updated_at = TIMESTAMPTZ '2026-08-02 15:30:00+00'
			WHERE teacher_id = ? AND id IN (
				SELECT alert.id FROM engagement_alerts alert
				JOIN detection_signal_results signal ON signal.id = alert.detection_signal_result_id
				JOIN detection_runs run ON run.id = signal.detection_run_id
				WHERE run.analysis_date = ?
			)
			""", TEACHER, WEEK_START);

		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_END.toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].date").value(WEEK_START.toString()))
			.andExpect(jsonPath("$.items[0].eventCount").value(3));
	}

	@Test
	void calendarAllowsFutureWeeksAndKeepsTheEmptyResponseShape() throws Exception {
		LocalDate futureStart = WEEK_START.plusWeeks(4);
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", futureStart.toString())
				.param("endedAt", futureStart.plusDays(6).toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(7))
			.andExpect(jsonPath("$.items[0].eventCount").value(0))
			.andExpect(jsonPath("$.items[6].eventCount").value(0));
	}

	@Test
	void calendarRejectsMissingMalformedAndInvalidRanges() throws Exception {
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"));
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", "2026/08/03")
				.param("endedAt", WEEK_END.toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DATE_FORMAT"));
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_START.minusDays(1).toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CALENDAR_RANGE"));
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_END.plusDays(7).toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CALENDAR_RANGE"));
	}

	@Test
	void calendarUsesOnlyPrincipalTenantAndRequiresTeacherAuthentication() throws Exception {
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_END.toString())
				.param("teacherId", OTHER_TEACHER.toString())
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[2].eventCount").value(3));
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_END.toString()))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/dashboard/calendar")
				.param("startedAt", WEEK_START.toString())
				.param("endedAt", WEEK_END.toString())
				.with(parentAuthentication()))
			.andExpect(status().isForbidden());
	}

	private void insertStudent(UUID studentId, String alias, String aiAlias, UUID teacherId) {
		jdbc.update("INSERT INTO student_profiles(id,alias,grade,created_at,updated_at) VALUES (?,?,1,?,?)",
			studentId, alias, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
		jdbc.update("INSERT INTO ai_student_aliases(teacher_id,student_id,alias,created_at) VALUES (?,?,?,?)",
			teacherId, studentId, aiAlias, NOW.atOffset(ZoneOffset.UTC));
	}

	private void insertRelationship(UUID teacherId, UUID studentId) {
		jdbc.update("INSERT INTO teacher_student_relationships(teacher_id,student_id,status,started_at,created_at) VALUES(?,?,'ACTIVE',?,?)",
			teacherId, studentId, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
	}

	private void insertRealName(UUID teacherId, UUID studentId, String realName) {
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes());
		jdbc.update("INSERT INTO student_personal_information(student_id,real_name,updated_by_account_id,updated_by_role,created_at,updated_at) VALUES(?,?,?,'TEACHER',?,?)",
			studentId, realName, accountId,
			NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
	}

	private void insertInterventionWithReminder(
		UUID interventionId, UUID alertId, Instant createdAt, Instant scheduledAt
	) {
		jdbc.update("""
			INSERT INTO interventions(id,teacher_id,student_id,alert_id,type,content,status,
			 created_at,updated_at) VALUES (?,?,?,?, 'CALL','private content','OPEN',?,?)
			""", interventionId, TEACHER, STUDENT, alertId,
			createdAt.atOffset(ZoneOffset.UTC), createdAt.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO intervention_reminders(teacher_id,intervention_id,scheduled_at,status,
			 created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,?)
			""", TEACHER, interventionId, scheduledAt.atOffset(ZoneOffset.UTC),
			createdAt.atOffset(ZoneOffset.UTC), createdAt.atOffset(ZoneOffset.UTC));
	}

	private void insertRunWithAlerts(
		UUID teacherId, UUID studentId, LocalDate date, String key, String alias
	) {
		UUID runId = UUID.nameUUIDFromBytes(("run:" + key).getBytes());
		jdbc.update("""
			INSERT INTO detection_runs(id,teacher_id,analysis_date,week_start,idempotency_key,
			 snapshot_hash,snapshot_payload,status,prepared_at,requested_at,completed_at,
			 ai_execution_id,created_at,updated_at)
			VALUES (?,?,?,?,?,'sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
			 '{}','SUCCEEDED',?,?,?,'exec',?,?)
			""", runId, teacherId, date, date.minusDays(date.getDayOfWeek().getValue() - 1L), key,
			NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC),
			NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
		insertAlert(runId, teacherId, studentId, alias, key, 1, "PENDING_REVIEW", "R1", false);
		insertAlert(runId, teacherId, studentId, alias, key, 3, "REJECTED", "R2", true);
		insertAlert(runId, teacherId, studentId, alias, key, 2, "APPROVED", "R3", false);
	}

	private void insertAlert(
		UUID runId, UUID teacherId, UUID studentId, String alias, String key,
		int rank, String status, String ruleId, boolean fallback
	) {
		String suffix = ruleId.substring(1);
		String classRef = teacherId.equals(TEACHER)
			? "cl_" + CLASS.toString().replace("-", "")
			: "class";
		UUID signalId = UUID.nameUUIDFromBytes(("signal:" + key + ruleId).getBytes());
		UUID alertId = UUID.nameUUIDFromBytes(("alert:" + key + ruleId).getBytes());
		jdbc.update("""
			INSERT INTO detection_signal_results(id,detection_run_id,external_signal_id,student_ref,
			 class_ref,rule_id,signal_type,display_label,score,rank,lifecycle,brief_text,
			 gate_passed,fallback_used,created_at)
			VALUES (?,?,?,?,?,?,?,?,0.8,?,'NEW',?,true,?,?)
			""", signalId, runId, "signal-" + key + ruleId, alias, classRef, ruleId,
			"hidden_risk", "위험", rank, "브리핑 " + key + " " + suffix, fallback,
			NOW.atOffset(ZoneOffset.UTC));
		jdbc.update("INSERT INTO detection_result_evidence(detection_signal_result_id,source_hint,record_id,summary) VALUES (?,'learning_records',?,?)",
			signalId, "record-" + key + "-" + suffix, "근거 " + key + " " + suffix);
		String note = status.equals("REJECTED") ? "오경보" : null;
		Object decidedAt = status.equals("PENDING_REVIEW") ? null : NOW.atOffset(ZoneOffset.UTC);
		jdbc.update("INSERT INTO engagement_alerts(id,teacher_id,student_id,detection_signal_result_id,status,decision_note,decided_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
			alertId, teacherId, studentId, signalId, status, note, decidedAt,
			NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new AuthenticatedAccount(UUID.randomUUID(), AccountRole.TEACHER, teacherId, UUID.randomUUID()),
			null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parentAuthentication() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new AuthenticatedAccount(UUID.randomUUID(), AccountRole.PARENT, null, UUID.randomUUID()),
			null, List.of(new SimpleGrantedAuthority("ROLE_PARENT"))));
	}
}
