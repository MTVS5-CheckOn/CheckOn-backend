package com.checkon.learning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.checkon.detection.application.AiDetectionConsentMode;
import com.checkon.detection.application.AiDetectionConsentPolicy;
import com.checkon.detection.application.PrepareDetectionRunService;
import com.checkon.detection.infrastructure.persistence.DetectionEvidenceProjectionRepository;
import com.checkon.detection.integration.ai.AiDetectionConsentProperties;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.domain.LearningRecordType;
import com.checkon.learning.infrastructure.persistence.LearningRecordRepository;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.domain.TeacherStudentRelationship;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

class LearningRecordSnapshotServiceTest {

	private static final UUID TEACHER = UUID.fromString("0198a000-0000-7000-8000-000000000001");
	private static final UUID STUDENT = UUID.fromString("0198a000-0000-7000-8000-000000000002");
	private static final UUID RECORD = UUID.fromString("0198a000-0000-7000-8000-000000000003");
	private static final UUID CLASS = UUID.fromString("0198a000-0000-7000-8000-000000000004");
	private static final Instant FROM = Instant.parse("2026-07-27T00:00:00Z");

	@Test
	void temporaryPreConsentPolicyBuildsGrantedSnapshotForAnalysis() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, true);

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).singleElement().satisfies(student -> {
			assertThat(student.studentRef()).isEqualTo("st_demo_student");
			assertThat(student.consent()).isEqualTo("granted");
		});
		assertThat(snapshot.learningEvents()).singleElement();
		assertThat(snapshot.snapshotMeta().classes()).singleElement();
		assertThat(snapshot.detectionEvidence()).hasSize(20).allSatisfy(evidence ->
			assertThat(evidence.studentRef()).isEqualTo("st_demo_student")
		);
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.kind().equals("weekly_activity"))
			.filteredOn(evidence -> evidence.weekStart().equals(LocalDate.parse("2026-07-27")))
			.singleElement()
			.satisfies(evidence -> assertThat(evidence.activityCount()).isEqualTo(1));
	}

	@Test
	@org.junit.jupiter.api.DisplayName("Given an active student with no learning events, When building a snapshot, Then zero weekly activity is sent")
	void givenActiveStudentWithoutEvents_whenBuildingSnapshot_thenSendsZeroActivityEvidence() {
		Fixture fixture = fixture(AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL, false);

		var snapshot = fixture.service().build(
			TEACHER, LocalDate.parse("2026-07-27"), "normal", FROM, FROM.plusSeconds(60)
		);

		assertThat(snapshot.students()).singleElement();
		assertThat(snapshot.learningEvents()).isEmpty();
		assertThat(snapshot.detectionEvidence()).hasSize(20);
		assertThat(snapshot.detectionEvidence())
			.filteredOn(evidence -> evidence.kind().equals("weekly_activity"))
			.filteredOn(evidence -> evidence.weekStart().equals(LocalDate.parse("2026-07-27")))
			.singleElement()
			.satisfies(evidence -> assertThat(evidence.activityCount()).isZero());
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

	private Fixture fixture(AiDetectionConsentMode mode, boolean includeRecord) {
		LearningRecordRepository records = mock(LearningRecordRepository.class);
		ClassEnrollmentRepository enrollments = mock(ClassEnrollmentRepository.class);
		TeacherStudentRelationshipRepository relationships = mock(
			TeacherStudentRelationshipRepository.class
		);
		DetectionEvidenceProjectionRepository evidenceProjections = mock(
			DetectionEvidenceProjectionRepository.class
		);
		AiStudentAliasService aliases = mock(AiStudentAliasService.class);
		PrepareDetectionRunService prepareService = mock(PrepareDetectionRunService.class);
		TeacherTenantDatabaseContext tenantContext = mock(TeacherTenantDatabaseContext.class);
		LearningRecord record = record();

		when(records.findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
			eq(TEACHER), any(Instant.class), any(Instant.class)
		)).thenReturn(includeRecord ? List.of(record) : List.of());
		when(enrollments.findAllByTeacherIdAndStatus(TEACHER, RelationshipStatus.ACTIVE))
			.thenReturn(List.of());
		when(relationships.findAllByTeacherIdAndStatus(TEACHER, RelationshipStatus.ACTIVE))
			.thenReturn(List.of(TeacherStudentRelationship.start(
				TEACHER, STUDENT, FROM.minusSeconds(60), FROM.minusSeconds(60)
			)));
		if (mode == AiDetectionConsentMode.PRE_CONSENT_ALLOW_ALL) {
			when(aliases.getOrCreate(TEACHER, STUDENT)).thenReturn("st_demo_student");
		}

		AiDetectionConsentPolicy consentPolicy = new AiDetectionConsentPolicy(
			new AiDetectionConsentProperties(mode)
		);
		return new Fixture(new LearningRecordSnapshotService(
			records, enrollments, relationships, evidenceProjections, aliases, consentPolicy,
			prepareService, tenantContext
		), aliases);
	}

	private LearningRecord record() {
		LearningRecord record = mock(LearningRecord.class);
		when(record.id()).thenReturn(RECORD);
		when(record.teacherId()).thenReturn(TEACHER);
		when(record.studentId()).thenReturn(STUDENT);
		when(record.classGroupId()).thenReturn(CLASS);
		when(record.recordType()).thenReturn(LearningRecordType.SOLVE);
		when(record.occurredAt()).thenReturn(FROM.plusSeconds(1));
		when(record.sourceType()).thenReturn("manual");
		return record;
	}

	private record Fixture(LearningRecordSnapshotService service, AiStudentAliasService aliases) {
	}
}
