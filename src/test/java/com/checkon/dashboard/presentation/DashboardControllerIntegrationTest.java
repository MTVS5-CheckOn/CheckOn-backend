package com.checkon.dashboard.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class DashboardControllerIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f000-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198f000-0000-7000-8000-000000000002");
	private static final UUID STUDENT = UUID.fromString("0198f000-0000-7000-8000-000000000003");
	private static final UUID OTHER_STUDENT = UUID.fromString("0198f000-0000-7000-8000-000000000004");
	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM intervention_reminders");
		jdbc.update("DELETE FROM interventions");
		jdbc.update("DELETE FROM engagement_alerts");
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		insertStudent(STUDENT, "실명처럼 보이는 별칭", "st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", TEACHER);
		insertStudent(OTHER_STUDENT, "다른 강사 학생", "st_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", OTHER_TEACHER);
		insertRunWithAlerts(TEACHER, STUDENT, TODAY, "today", "st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		insertRunWithAlerts(OTHER_TEACHER, OTHER_STUDENT, TODAY, "other", "st_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		insertRunWithAlerts(TEACHER, STUDENT, TODAY.minusDays(2), "past", "st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
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
			.andExpect(jsonPath("$.alerts[0].brief").value("브리핑 today 1"))
			.andExpect(jsonPath("$.alerts[0].briefFallback").value(false))
			.andExpect(jsonPath("$.alerts[0].evidence[0].recordId").value("record-today-1"))
			.andExpect(jsonPath("$.alerts[0].evidence[0].summary").value("근거 today 1"))
			.andExpect(jsonPath("$.alerts[0].status").value("PENDING_REVIEW"))
			.andExpect(jsonPath("$.alerts[1].status").value("APPROVED"))
			.andExpect(jsonPath("$.alerts[2].status").value("REJECTED"))
			.andExpect(jsonPath("$.alerts[0].studentName").doesNotExist())
			.andExpect(jsonPath("$.alerts[0].feedbackGiven").doesNotExist());
	}

	@Test
	void pastAndEmptyDatesReturnNormally() throws Exception {
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.minusDays(2).toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.alerts.length()").value(3));
		mvc.perform(get("/api/v1/dashboard/briefing")
				.param("date", TODAY.minusDays(1).toString()).with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.alerts").isEmpty());
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

	private void insertStudent(UUID studentId, String alias, String aiAlias, UUID teacherId) {
		jdbc.update("INSERT INTO student_profiles(id,alias,grade,created_at,updated_at) VALUES (?,?,1,?,?)",
			studentId, alias, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
		jdbc.update("INSERT INTO ai_student_aliases(teacher_id,student_id,alias,created_at) VALUES (?,?,?,?)",
			teacherId, studentId, aiAlias, NOW.atOffset(ZoneOffset.UTC));
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
		UUID signalId = UUID.nameUUIDFromBytes(("signal:" + key + ruleId).getBytes());
		UUID alertId = UUID.nameUUIDFromBytes(("alert:" + key + ruleId).getBytes());
		jdbc.update("""
			INSERT INTO detection_signal_results(id,detection_run_id,external_signal_id,student_ref,
			 class_ref,rule_id,signal_type,display_label,score,rank,lifecycle,brief_text,
			 gate_passed,fallback_used,created_at)
			VALUES (?,?,?,?,?,?,?,?,0.8,?,'NEW',?,true,?,?)
			""", signalId, runId, "signal-" + key + ruleId, alias, "class", ruleId,
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
