package com.checkon.learning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.checkon.detection.application.AiDetectionConsentMode;
import com.checkon.detection.application.AiDetectionConsentPolicy;
import com.checkon.detection.application.DetectionStudentStatusHistoryService;
import com.checkon.detection.application.PrepareDetectionRunService;
import com.checkon.detection.integration.ai.AiDetectionConsentProperties;
import com.checkon.engagement.application.EngagementAlertContextService;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.domain.LearningRecordType;
import com.checkon.learning.infrastructure.persistence.LearningRecordRepository;
import com.checkon.roster.domain.ClassEnrollment;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.domain.TeacherStudentRelationship;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

class LearningRecordSnapshotServiceTest {

	private static final UUID TEACHER = UUID.fromString("0198a000-0000-7000-8000-000000000001");
	private static final UUID STUDENT = UUID.fromString("0198a000-0000-7000-8000-000000000002");
	private static final UUID STUDENT_2 = UUID.fromString("0198a000-0000-7000-8000-000000000005");
	private static final UUID RECORD = UUID.fromString("0198a000-0000-7000-8000-000000000003");
	private static final UUID CLASS = UUID.fromString("0198a000-0000-7000-8000-000000000004");
	private static final Instant FROM = Instant.parse("2026-07-27T00:00:00Z");

	@Test
	@org.junit.jupiter.api.DisplayName("Given 임시 동의 정책의 ACTIVE 학생, When 스냅샷을 만들면, Then enrolled 상태와 MANUAL source를 유지한다")
	void givenTemporaryConsentActiveStudent_whenBuildingSnapshot_thenKeepsEnrolledAndManualSource() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true);

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).singleElement().satisfies(student -> {
			assertThat(student.studentRef()).isEqualTo("st_demo_student");
			assertThat(student.consent()).isEqualTo("granted");
			assertThat(student.status()).isEqualTo("enrolled");
		});
		assertThat(snapshot.learningEvents()).singleElement().satisfies(event ->
			assertThat(event.source()).isEqualTo("MANUAL")
		);
		assertThat(snapshot.snapshotMeta().classes()).singleElement();
		assertThat(snapshot.detectionEvidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.kind()).isEqualTo("weekly_activity");
			assertThat(evidence.studentRef()).isEqualTo("st_demo_student");
		});
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.kind().equals("weekly_activity"))
			.filteredOn(evidence -> evidence.weekStart().equals(LocalDate.parse("2026-07-27")))
			.singleElement()
			.satisfies(evidence -> assertThat(evidence.activityCount()).isEqualTo(1));
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given 최근 복귀 이력, When 스냅샷을 만들면, Then returned 학생과 enrollment transition을 보낸다")
	void givenRecentReturnHistory_whenBuildingSnapshot_thenSendsR5Evidence() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true);
		Instant returnedAt = FROM.plusSeconds(10);
		when(fixture.statusHistory().findReturnedTransitions(
			eq(TEACHER), any(Instant.class), any(Instant.class)
		)).thenReturn(List.of(new DetectionStudentStatusHistoryService.ReturnedTransition(
			UUID.fromString("0198a000-0000-7000-8000-000000000006"),
			STUDENT,
			returnedAt
		)));

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).singleElement().satisfies(student ->
			assertThat(student.status()).isEqualTo("returned")
		);
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.kind().equals("enrollment_transition"))
			.singleElement().satisfies(evidence -> {
				assertThat(evidence.sourceTable()).isEqualTo("student_status_history");
				assertThat(evidence.recordId()).isEqualTo(
					"status-history:st_demo_student:2026-07-27T09:00:10+09:00"
				);
				assertThat(evidence.studentRef()).isEqualTo("st_demo_student");
				assertThat(evidence.fromStatus()).isEqualTo("paused");
				assertThat(evidence.toStatus()).isEqualTo("returned");
			});
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given an active student with no learning events, When building a snapshot, Then zero weekly activity is sent")
	void givenActiveStudentWithoutEvents_whenBuildingSnapshot_thenSendsZeroActivityEvidence() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, false);

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).singleElement().satisfies(student -> {
			assertThat(student.classRef()).isEqualTo("cl_unassigned");
			assertThat(student.status()).isEqualTo("enrolled");
		});
		assertThat(snapshot.learningEvents()).isEmpty();
		assertThat(snapshot.snapshotMeta().classes()).isEmpty();
		assertThat(snapshot.detectionEvidence()).hasSize(1);
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.kind().equals("weekly_activity"))
			.filteredOn(evidence -> evidence.weekStart().equals(LocalDate.parse("2026-07-27")))
			.singleElement()
			.satisfies(evidence -> assertThat(evidence.activityCount()).isZero());
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given 관계 시작 전 주차가 포함된 조회 범위, When 스냅샷을 만들면, Then 시작 전 행은 생략하고 시작 후 0건은 보낸다")
	void givenWeeksBeforeRelationship_whenBuildingSnapshot_thenOmitsUnknownWeeksAndKeepsObservedZero() {
		// Given
		Instant relationshipStartedAt = Instant.parse("2026-07-13T00:00:00Z");
		Fixture fixture = fixture(
			AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true, true,
			relationshipStartedAt
		);

		// When
		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		// Then
		assertThat(snapshot.students()).singleElement().satisfies(student -> {
			assertThat(student.status()).isEqualTo("enrolled");
			assertThat(student.enrolledWeeks()).isEqualTo(2);
		});
		assertThat(snapshot.detectionEvidence())
			.extracting(evidence -> evidence.weekStart())
			.containsExactly(
				LocalDate.parse("2026-07-13"),
				LocalDate.parse("2026-07-20"),
				LocalDate.parse("2026-07-27")
			);
		assertThat(snapshot.detectionEvidence())
			.extracting(evidence -> evidence.activityCount())
			.containsExactly(0, 0, 1);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given 최근 반 등록과 오래된 ACTIVE 관계, When 스냅샷을 만들면, Then 관계 시작일로 재원 기간을 계산한다")
	void givenRecentClassEnrollment_whenBuildingSnapshot_thenDoesNotResetRelationshipWeeks() {
		// Given
		Fixture fixture = fixture(
			AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true, true,
			FROM.minus(35, ChronoUnit.DAYS)
		);
		ClassEnrollment recentEnrollment = mock(ClassEnrollment.class);
		when(recentEnrollment.studentId()).thenReturn(STUDENT);
		when(recentEnrollment.classGroupId()).thenReturn(CLASS);
		when(recentEnrollment.enrolledAt()).thenReturn(FROM.minus(1, ChronoUnit.DAYS));
		when(fixture.enrollments().findAllByTeacherIdAndStatus(
			TEACHER, RelationshipStatus.ACTIVE
		)).thenReturn(List.of(recentEnrollment));

		// When
		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		// Then
		assertThat(snapshot.students()).singleElement().satisfies(student ->
			assertThat(student.enrolledWeeks()).isEqualTo(5)
		);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given 시작일이 다른 두 학생, When 스냅샷을 만들면, Then 학생별 weekly activity 시작 주를 독립 적용한다")
	void givenStudentsWithDifferentStartDates_whenBuildingSnapshot_thenAppliesCutoffPerStudent() {
		// Given
		Fixture fixture = fixture(
			AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true, true,
			Instant.parse("2026-07-13T00:00:00Z")
		);
		TeacherStudentRelationship secondRelationship = TeacherStudentRelationship.start(
			TEACHER, STUDENT_2, Instant.parse("2026-07-27T00:00:00Z"),
			Instant.parse("2026-07-27T00:00:00Z")
		);
		when(fixture.relationships().findAllByTeacherIdAndStatus(
			TEACHER, RelationshipStatus.ACTIVE
		)).thenReturn(List.of(
			TeacherStudentRelationship.start(
				TEACHER, STUDENT, Instant.parse("2026-07-13T00:00:00Z"),
				Instant.parse("2026-07-13T00:00:00Z")
			),
			secondRelationship
		));
		when(fixture.aliases().getOrCreate(TEACHER, STUDENT_2))
			.thenReturn("st_demo_student_2");

		// When
		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		// Then
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.studentRef().equals("st_demo_student"))
			.hasSize(3);
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.studentRef().equals("st_demo_student_2"))
			.singleElement()
			.satisfies(evidence -> assertThat(evidence.weekStart())
				.isEqualTo(LocalDate.parse("2026-07-27")));
	}

	@Test
	void recordedGrantModeExcludesStudentsWithoutAConsentSource() {
		Fixture fixture = fixture(AiDetectionConsentMode.REQUIRE_RECORDED_GRANT, true);

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).isEmpty();
		assertThat(snapshot.learningEvents()).isEmpty();
		assertThat(snapshot.snapshotMeta().classes()).isEmpty();
		verifyNoInteractions(fixture.aliases());
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given 종료 학생의 과거 기록, When 스냅샷을 만들면, Then 학생과 기록을 AI 요청에서 제외한다")
	void givenEndedStudentHistory_whenBuildingSnapshot_thenExcludesFormerStudent() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true, false);

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).isEmpty();
		assertThat(snapshot.learningEvents()).isEmpty();
		assertThat(snapshot.detectionEvidence()).isEmpty();
		assertThat(snapshot.alertContext()).isEmpty();
		verifyNoInteractions(fixture.aliases());
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given 기존 해결 경보, When 스냅샷을 만들면, Then AI alias 기반 alert_context를 채운다")
	void givenResolvedAlertHistory_whenBuildingSnapshot_thenAddsPseudonymousAlertContext() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true);
		when(fixture.alertContexts().latestByStudentAndSignalType(TEACHER)).thenReturn(List.of(
			new EngagementAlertContextService.AlertHistory(
				STUDENT, "hidden_risk", "resolved", Instant.parse("2026-08-05T10:00:00Z"), true
			)
		));

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.alertContext()).singleElement().satisfies(context -> {
			assertThat(context.studentRef()).isEqualTo("st_demo_student");
			assertThat(context.signalType()).isEqualTo("hidden_risk");
			assertThat(context.status()).isEqualTo("resolved");
			assertThat(context.resolvedAt().toInstant())
				.isEqualTo(Instant.parse("2026-08-05T10:00:00Z"));
			assertThat(context.followedUp()).isTrue();
		});
	}

	private Fixture fixture(AiDetectionConsentMode mode, boolean includeRecord) {
		return fixture(mode, includeRecord, true);
	}

	private Fixture fixture(
		AiDetectionConsentMode mode,
		boolean includeRecord,
		boolean activeRelationship
	) {
		return fixture(mode, includeRecord, activeRelationship, FROM.minusSeconds(60));
	}

	private Fixture fixture(
		AiDetectionConsentMode mode,
		boolean includeRecord,
		boolean activeRelationship,
		Instant relationshipStartedAt
	) {
		LearningRecordRepository records = mock(LearningRecordRepository.class);
		ClassEnrollmentRepository enrollments = mock(ClassEnrollmentRepository.class);
		TeacherStudentRelationshipRepository relationships = mock(
			TeacherStudentRelationshipRepository.class
		);
		EngagementAlertContextService alertContexts = mock(EngagementAlertContextService.class);
		AiStudentAliasService aliases = mock(AiStudentAliasService.class);
		PrepareDetectionRunService prepareService = mock(PrepareDetectionRunService.class);
		DetectionStudentStatusHistoryService statusHistory = mock(
			DetectionStudentStatusHistoryService.class
		);
		TeacherTenantDatabaseContext tenantContext = mock(TeacherTenantDatabaseContext.class);
		LearningRecord record = record();

		when(records.findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
			eq(TEACHER), any(Instant.class), any(Instant.class)
		)).thenReturn(includeRecord ? List.of(record) : List.of());
		when(enrollments.findAllByTeacherIdAndStatus(TEACHER, RelationshipStatus.ACTIVE))
			.thenReturn(List.of());
		when(alertContexts.latestByStudentAndSignalType(TEACHER)).thenReturn(List.of());
		when(statusHistory.findReturnedTransitions(
			eq(TEACHER), any(Instant.class), any(Instant.class)
		)).thenReturn(List.of());
		when(relationships.findAllByTeacherIdAndStatus(TEACHER, RelationshipStatus.ACTIVE))
			.thenReturn(activeRelationship ? List.of(TeacherStudentRelationship.start(
				TEACHER, STUDENT, relationshipStartedAt, relationshipStartedAt
			)) : List.of());
		if (mode == AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL) {
			when(aliases.getOrCreate(TEACHER, STUDENT)).thenReturn("st_demo_student");
		}

		AiDetectionConsentPolicy consentPolicy = new AiDetectionConsentPolicy(
			new AiDetectionConsentProperties(mode)
		);
		return new Fixture(new LearningRecordSnapshotService(
			records, enrollments, relationships, alertContexts, aliases,
			consentPolicy, statusHistory,
			prepareService, tenantContext
		), aliases, alertContexts, enrollments, relationships, statusHistory);
	}

	private LearningRecord record() {
		LearningRecord record = mock(LearningRecord.class);
		when(record.id()).thenReturn(RECORD);
		when(record.teacherId()).thenReturn(TEACHER);
		when(record.studentId()).thenReturn(STUDENT);
		when(record.classGroupId()).thenReturn(CLASS);
		when(record.recordType()).thenReturn(LearningRecordType.SOLVE);
		when(record.occurredAt()).thenReturn(FROM.plusSeconds(1));
		when(record.sourceType()).thenReturn("MANUAL");
		return record;
	}

	private record Fixture(
		LearningRecordSnapshotService service,
		AiStudentAliasService aliases,
		EngagementAlertContextService alertContexts,
		ClassEnrollmentRepository enrollments,
		TeacherStudentRelationshipRepository relationships,
		DetectionStudentStatusHistoryService statusHistory
	) {
	}
}
