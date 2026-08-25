package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.integration.ai.GuardianLabelClient;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionResponse;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest.Action;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@DisplayName("실제 학부모 라벨 제안 PostgreSQL 흐름")
class GuardianLabelSuggestionIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f900-0000-7000-8000-000000000a01");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198f900-0000-7000-8000-000000000a02");
	private static final UUID PARENT = UUID.fromString("0198f900-0000-7000-8000-000000000a11");
	private static final UUID STUDENT_ONE = UUID.fromString("0198f900-0000-7000-8000-000000000a21");
	private static final UUID STUDENT_TWO = UUID.fromString("0198f900-0000-7000-8000-000000000a22");
	private static final UUID CLASS_GROUP = UUID.fromString("0198f900-0000-7000-8000-000000000a31");
	private static final Instant BASE_TIME = Instant.parse("2026-08-25T00:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private GuardianLabelSuggestionService service;

	@Autowired
	private GuardianLabelDecisionService decisionService;

	@MockitoBean
	private GuardianLabelClient client;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM guardian_label_decisions");
		jdbc.update("DELETE FROM guardian_labels");
		jdbc.update("DELETE FROM guardian_label_suggestions");
		jdbc.update("DELETE FROM guardian_label_suggestion_requests");
		jdbc.update("DELETE FROM ai_parent_label_aliases");
		jdbc.update("DELETE FROM counsel_draft_kafka_outbox_events");
		jdbc.update("DELETE FROM counsel_draft_jobs");
		jdbc.update("DELETE FROM counsel_inquiries");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM parent_student_relationships");
		jdbc.update("DELETE FROM parent_teacher_relationships");
		jdbc.update("DELETE FROM parent_profiles");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");

		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		insertStudent(STUDENT_ONE, "첫째");
		insertStudent(STUDENT_TWO, "둘째");
		insertParent();
		insertClass();
		insertCommunication(STUDENT_ONE, "iq-1", "job-1", 1);
		insertCommunication(STUDENT_TWO, "iq-2", "job-2", 2);
		insertCommunication(STUDENT_ONE, "iq-3", "job-3", 3);
	}

	@Test
	@DisplayName("Given 두 학생의 상담 이력 여섯 건 When 제안하면 Then 실제 학부모 하나로 합쳐 +09:00 마스킹 요청하고 재요청은 캐시한다")
	void combinesLinkedStudentsAndCachesTheHistoryVersion() {
		when(client.suggest(any(), anyString(), anyString())).thenAnswer(invocation -> {
			GuardianLabelSuggestionRequest request = invocation.getArgument(0);
			var evidence = request.history().getFirst();
			return new GuardianLabelSuggestionResponse(
				new GuardianLabelSuggestionResponse.Data(List.of(new GuardianLabelSuggestionResponse.Suggestion(
					request.guardianRef() + ":comm:data", request.guardianRef(),
					new GuardianLabelSuggestionResponse.Label(GuardianLabelAxis.COMM, GuardianLabelValue.DATA),
					new BigDecimal("0.86"),
					List.of(new GuardianLabelSuggestionResponse.EvidenceQuote(evidence.recordId(), "문의"))
				))), null, null
			);
		});

		var first = service.suggest(TEACHER, PARENT);
		var second = service.suggest(TEACHER, PARENT);

		assertThat(first.eligible()).isTrue();
		assertThat(first.historyCount()).isEqualTo(6);
		assertThat(first.guardianRef()).startsWith("gd_");
		assertThat(first.suggestions()).hasSize(1);
		assertThat(second.cacheHit()).isTrue();
		assertThat(second.guardianRef()).isEqualTo(first.guardianRef());
		verify(client, times(1)).suggest(any(), anyString(), anyString());

		var request = org.mockito.ArgumentCaptor.forClass(GuardianLabelSuggestionRequest.class);
		verify(client).suggest(request.capture(), anyString(), anyString());
		assertThat(request.getValue().history()).hasSize(6).isSortedAccordingTo(
			java.util.Comparator.comparing(GuardianLabelSuggestionRequest.HistoryRecord::at)
		);
		assertThat(request.getValue().history()).allSatisfy(record -> {
			assertThat(record.at().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
			assertThat(record.text()).doesNotContain("010-1234-5678");
		});
	}

	@Test
	@DisplayName("Given 다른 강사만 연결된 실제 학부모 When 제안하면 Then 존재 여부를 숨긴다")
	void hidesAParentOutsideTheTeacherTenant() {
		assertThatThrownBy(() -> service.suggest(OTHER_TEACHER, PARENT))
			.isInstanceOf(GuardianLabelSuggestionException.class)
			.satisfies(exception -> assertThat(((GuardianLabelSuggestionException) exception).reason())
				.isEqualTo(GuardianLabelSuggestionException.Reason.TARGET_NOT_FOUND));
	}

	@Test
	@DisplayName("Given AI 라벨 제안 When 강사가 확정하고 같은 요청을 다시 보내면 Then 정본은 한 번 저장하고 AI에도 한 번만 알린다")
	void storesAConfirmedLabelAndDoesNotResendDuplicateFeedback() {
		stubCommSuggestion();
		var suggestion = service.suggest(TEACHER, PARENT).suggestions().getFirst();

		var first = decisionService.decide(TEACHER, PARENT, suggestion.suggestionRef(), Action.confirmed, null);
		var duplicate = decisionService.decide(TEACHER, PARENT, suggestion.suggestionRef(), Action.confirmed, null);

		assertThat(first.newlyCreated()).isTrue();
		assertThat(duplicate.newlyCreated()).isFalse();
		assertThat(decisionService.currentLabels(TEACHER, PARENT))
			.extracting(label -> label.axis().wireValue() + ":" + label.value().wireValue())
			.containsExactly("comm:data");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM guardian_label_decisions", Integer.class)).isEqualTo(1);
		verify(client, times(1)).confirm(any(), anyString(), anyString());
	}

	@Test
	@DisplayName("Given AI 통지가 실패할 때 When 강사가 정정하면 Then 백엔드 정본은 유지하고 재전송하지 않는다")
	void keepsTheCorrectedSourceOfTruthWhenFeedbackDeliveryFails() {
		stubCommSuggestion();
		var suggestion = service.suggest(TEACHER, PARENT).suggestions().getFirst();
		when(client.confirm(any(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));

		var result = decisionService.decide(
			TEACHER, PARENT, suggestion.suggestionRef(), Action.corrected, GuardianLabelValue.NARRATIVE
		);

		assertThat(result.value()).isEqualTo(GuardianLabelValue.NARRATIVE);
		assertThat(decisionService.currentLabels(TEACHER, PARENT))
			.extracting(label -> label.value()).containsExactly(GuardianLabelValue.NARRATIVE);
		verify(client, times(1)).confirm(any(), anyString(), anyString());
	}

	@Test
	@DisplayName("Given comm 제안 When 다른 축 값으로 정정하면 Then 저장하거나 AI에 알리지 않는다")
	void rejectsACrossAxisCorrectionBeforePersistence() {
		stubCommSuggestion();
		var suggestion = service.suggest(TEACHER, PARENT).suggestions().getFirst();

		assertThatThrownBy(() -> decisionService.decide(
			TEACHER, PARENT, suggestion.suggestionRef(), Action.corrected, GuardianLabelValue.ANXIOUS
		)).isInstanceOf(GuardianLabelSuggestionException.class)
			.satisfies(exception -> assertThat(((GuardianLabelSuggestionException) exception).reason())
				.isEqualTo(GuardianLabelSuggestionException.Reason.INVALID_DECISION));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM guardian_label_decisions", Integer.class)).isZero();
		verify(client, never()).confirm(any(), anyString(), anyString());
	}

	@Test
	@DisplayName("Given comm 제안 When DB에 sensitivity 값을 직접 정정하려 하면 Then 축-값 제약이 거절한다")
	void rejectsACrossAxisCorrectionAtTheDatabaseBoundary() {
		stubCommSuggestion();
		var suggestion = service.suggest(TEACHER, PARENT).suggestions().getFirst();

		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO guardian_label_decisions (
			 id, suggestion_row_id, teacher_id, parent_id, suggestion_id, axis,
			 action, decided_value, created_at
			) VALUES (?, ?, ?, ?, ?, 'comm', 'corrected', 'anxious', now())
			""", UUID.randomUUID(), suggestion.suggestionRef(), TEACHER, PARENT, suggestion.suggestionId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void stubCommSuggestion() {
		when(client.suggest(any(), anyString(), anyString())).thenAnswer(invocation -> {
			GuardianLabelSuggestionRequest request = invocation.getArgument(0);
			var evidence = request.history().getFirst();
			return new GuardianLabelSuggestionResponse(
				new GuardianLabelSuggestionResponse.Data(List.of(new GuardianLabelSuggestionResponse.Suggestion(
					request.guardianRef() + ":comm:data", request.guardianRef(),
					new GuardianLabelSuggestionResponse.Label(GuardianLabelAxis.COMM, GuardianLabelValue.DATA),
					new BigDecimal("0.86"),
					List.of(new GuardianLabelSuggestionResponse.EvidenceQuote(evidence.recordId(), "문의"))
				))), null, null
			);
		});
	}

	private void insertStudent(UUID studentId, String alias) {
		jdbc.update("INSERT INTO student_profiles (id, alias, created_at, updated_at) VALUES (?, ?, ?, ?)",
			studentId, alias, dbTime(BASE_TIME), dbTime(BASE_TIME));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, ended_at, created_at)
			VALUES (?, ?, ?, 'ACTIVE', ?, NULL, ?)
			""", UUID.randomUUID(), TEACHER, studentId, dbTime(BASE_TIME), dbTime(BASE_TIME));
	}

	private void insertParent() {
		UUID accountId = UUID.fromString("0198f900-0000-7000-8000-000000000a12");
		jdbc.update("""
			INSERT INTO accounts (id, email, role, status, created_at)
			VALUES (?, ?, 'PARENT', 'ACTIVE', ?) ON CONFLICT (id) DO NOTHING
			""",
			accountId, "guardian-label-parent@test.local", dbTime(BASE_TIME));
		jdbc.update("INSERT INTO parent_profiles (id, account_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
			PARENT, accountId, dbTime(BASE_TIME), dbTime(BASE_TIME));
		jdbc.update("""
			INSERT INTO parent_teacher_relationships
			(id, parent_id, teacher_id, status, started_at, ended_at, created_at)
			VALUES (?, ?, ?, 'ACTIVE', ?, NULL, ?)
			""", UUID.randomUUID(), PARENT, TEACHER, dbTime(BASE_TIME), dbTime(BASE_TIME));
		for (UUID student : List.of(STUDENT_ONE, STUDENT_TWO)) {
			jdbc.update("""
				INSERT INTO parent_student_relationships
				(id, parent_id, student_id, status, started_at, ended_at, created_at)
				VALUES (?, ?, ?, 'ACTIVE', ?, NULL, ?)
				""", UUID.randomUUID(), PARENT, student, dbTime(BASE_TIME), dbTime(BASE_TIME));
		}
	}

	private void insertClass() {
		jdbc.update("""
			INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, '라벨반', '수학', 'ACTIVE', ?, ?)
			""", CLASS_GROUP, TEACHER, dbTime(BASE_TIME), dbTime(BASE_TIME));
	}

	private void insertCommunication(UUID studentId, String inquiryRef, String jobId, int order) {
		Instant receivedAt = BASE_TIME.plusSeconds(order * 120L);
		jdbc.update("""
			INSERT INTO counsel_inquiries (
			 id, teacher_id, inquiry_ref, student_id, class_id, topic, urgency, received_at,
			 raw_text, labels, dismissed_suggestions, period_label, facts, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, 'grade', 'normal', ?, ?, '[]', '[]', '2026년 8월', '[]', ?, ?)
			""", UUID.randomUUID(), TEACHER, inquiryRef, studentId, CLASS_GROUP, dbTime(receivedAt),
			"문의 010-1234-5678 " + order, dbTime(receivedAt), dbTime(receivedAt));
		jdbc.update("""
			INSERT INTO counsel_draft_jobs (
			 id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref, topic,
			 idempotency_key, job_id, job_phase, requested_at, updated_at, sent_text, sent_at
			) VALUES (?, ?, 'tn_old', ?, ?, ?, 'cl_old', 'grade', ?, ?, 'succeeded', ?, ?, ?, ?)
			""", UUID.randomUUID(), TEACHER, inquiryRef, "st_" + order, "pa_" + order,
			"idem-" + order, jobId, dbTime(receivedAt), dbTime(receivedAt.plusSeconds(30)),
			"발송 문의 010-1234-5678 " + order, dbTime(receivedAt.plusSeconds(60)));
	}

	private java.time.OffsetDateTime dbTime(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
