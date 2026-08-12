package com.checkon.learning.application;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.application.AiDetectionConsentPolicy;
import com.checkon.detection.application.AiDetectionConsentPolicy.Decision;
import com.checkon.detection.application.PrepareDetectionRunService;
import com.checkon.detection.application.PrepareDetectionRunService.PreparedDetectionRun;
import com.checkon.detection.infrastructure.persistence.DetectionEvidenceProjectionRepository;
import com.checkon.detection.infrastructure.persistence.DetectionEvidenceProjectionRepository.AssignmentWeekSummary;
import com.checkon.detection.infrastructure.persistence.DetectionEvidenceProjectionRepository.StudentStatusTransition;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.engagement.application.EngagementAlertContextService;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.infrastructure.persistence.LearningRecordRepository;
import com.checkon.roster.domain.ClassEnrollment;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

/** Builds the minimum AI contract from backend-owned, pseudonymized records. */
@Service
public class LearningRecordSnapshotService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int EVIDENCE_WEEK_COUNT = 10;
	private static final String ASSIGNMENT_WEEK_SUMMARY = "assignment_week_summary";
	private static final String STUDENT_WEEK_ACTIVITY = "student_week_activity";
	private static final String STUDENT_STATUS_HISTORY = "student_status_history";

	private static final Comparator<LearningRecord> RECORD_ORDER =
		Comparator.comparing(LearningRecord::occurredAt).thenComparing(LearningRecord::id);
	private static final Comparator<AiDetectionRequest.DetectionEvidence> EVIDENCE_ORDER =
		Comparator.comparing(AiDetectionRequest.DetectionEvidence::kind)
			.thenComparing(AiDetectionRequest.DetectionEvidence::studentRef)
			.thenComparing(LearningRecordSnapshotService::evidenceTime)
			.thenComparing(AiDetectionRequest.DetectionEvidence::sourceTable)
			.thenComparing(AiDetectionRequest.DetectionEvidence::recordId);

	private final LearningRecordRepository records;
	private final ClassEnrollmentRepository enrollments;
	private final TeacherStudentRelationshipRepository relationships;
	private final DetectionEvidenceProjectionRepository evidenceProjections;
	private final EngagementAlertContextService alertContexts;
	private final AiStudentAliasService aliases;
	private final AiDetectionConsentPolicy consentPolicy;
	private final PrepareDetectionRunService prepareService;
	private final TeacherTenantDatabaseContext tenantContext;

	public LearningRecordSnapshotService(LearningRecordRepository records,
		ClassEnrollmentRepository enrollments,
		TeacherStudentRelationshipRepository relationships,
		DetectionEvidenceProjectionRepository evidenceProjections,
		EngagementAlertContextService alertContexts,
		AiStudentAliasService aliases,
		AiDetectionConsentPolicy consentPolicy,
		PrepareDetectionRunService prepareService, TeacherTenantDatabaseContext tenantContext) {
		this.records = records; this.enrollments = enrollments; this.aliases = aliases;
		this.relationships = relationships; this.evidenceProjections = evidenceProjections;
		this.alertContexts = alertContexts;
		this.consentPolicy = consentPolicy;
		this.prepareService = prepareService; this.tenantContext = tenantContext;
	}

	@Transactional
	public PreparedDetectionRun prepare(UUID teacherId, String tenantAlias,
		LocalDate analysisDate, LocalDate weekStart, String termContext,
		Instant fromInclusive, Instant toExclusive) {
		AiDetectionRequest request = build(teacherId, weekStart, termContext,
			fromInclusive, toExclusive);
		return prepareService.prepare(teacherId, tenantAlias, analysisDate, request);
	}

	@Transactional
	public AiDetectionRequest build(UUID teacherId, LocalDate weekStart,
		String termContext, Instant fromInclusive, Instant toExclusive) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(weekStart, "weekStart must not be null");
		Objects.requireNonNull(fromInclusive, "fromInclusive must not be null");
		Objects.requireNonNull(toExclusive, "toExclusive must not be null");
		if (!fromInclusive.isBefore(toExclusive))
			throw new IllegalArgumentException("snapshot period must not be empty");
		if (termContext == null || termContext.isBlank())
			throw new IllegalArgumentException("termContext must not be blank");
		// Repository teacherId makes ownership visible in code; RLS independently
		// blocks accidental or direct cross-tenant SQL.
		tenantContext.setCurrentTeacher(teacherId);
		List<LearningRecord> ordered = records
			.findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
				teacherId, fromInclusive, toExclusive)
			.stream().sorted(RECORD_ORDER).toList();
		LocalDate firstEvidenceWeek = weekStart.minusWeeks(EVIDENCE_WEEK_COUNT - 1L);
		Instant evidenceFrom = firstEvidenceWeek.atStartOfDay(SERVICE_ZONE).toInstant();
		Instant evidenceTo = weekStart.plusWeeks(1).atStartOfDay(SERVICE_ZONE).toInstant();
		List<LearningRecord> activityRecords = records
			.findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
				teacherId, evidenceFrom, evidenceTo)
			.stream().sorted(RECORD_ORDER).toList();

		// Absence can be a signal, so active students must not disappear merely
		// because they have no event in the current 56-day learning-event range.
		List<UUID> activeStudentIds = relationships
			.findAllByTeacherIdAndStatus(teacherId, RelationshipStatus.ACTIVE)
			.stream().map(relationship -> relationship.studentId())
			.sorted().toList();
		// Historical records remain backend-owned, but an ended relationship must
		// not make a former student part of the current AI request.
		List<UUID> snapshotStudentIds = List.copyOf(new TreeSet<>(activeStudentIds));
		Map<UUID, ClassEnrollment> activeEnrollments = new LinkedHashMap<>();
		for (ClassEnrollment enrollment : enrollments.findAllByTeacherIdAndStatus(
			teacherId, RelationshipStatus.ACTIVE)) {
			activeEnrollments.put(enrollment.studentId(), enrollment);
		}
		Map<UUID, Decision> consentByStudent = new LinkedHashMap<>();
		for (UUID studentId : snapshotStudentIds) {
			consentByStudent.put(studentId, consentPolicy.decide(teacherId, studentId));
		}
		Map<UUID, String> aliasByStudent = new LinkedHashMap<>();
		for (UUID studentId : snapshotStudentIds) {
			if (consentByStudent.get(studentId).included()) {
				aliasByStudent.put(studentId, aliases.getOrCreate(teacherId, studentId));
			}
		}

		Map<UUID, LearningRecord> latestByStudent = new LinkedHashMap<>();
		for (LearningRecord record : activityRecords) latestByStudent.put(record.studentId(), record);
		List<StudentStatusTransition> transitions = evidenceProjections.findTransitions(
			teacherId,
			weekStart.atStartOfDay(SERVICE_ZONE).toInstant(),
			weekStart.plusWeeks(1).atStartOfDay(SERVICE_ZONE).toInstant()
		);
		Map<UUID, Boolean> returnedThisWeek = new LinkedHashMap<>();
		for (StudentStatusTransition transition : transitions) {
			if ("paused".equals(transition.fromStatus())
				&& "returned".equals(transition.toStatus())) {
				returnedThisWeek.put(transition.studentId(), true);
			}
		}
		List<AiDetectionRequest.StudentSnapshot> students = new ArrayList<>();
		for (var entry : aliasByStudent.entrySet()) {
			UUID studentId = entry.getKey();
			ClassEnrollment enrollment = activeEnrollments.get(studentId);
			LearningRecord latestRecord = latestByStudent.get(studentId);
			UUID classId = enrollment != null ? enrollment.classGroupId()
				: latestRecord == null ? null : latestRecord.classGroupId();
			int weeks = enrollment == null ? 0 : Math.max(0,
				(int) (Duration.between(enrollment.enrolledAt(), toExclusive).toDays() / 7));
			students.add(new AiDetectionRequest.StudentSnapshot(entry.getValue(),
				classRef(classId), weeks, enrollment == null ? "paused" : "enrolled",
				consentByStudent.get(studentId).requestConsent()));
			if (Boolean.TRUE.equals(returnedThisWeek.get(studentId))) {
				AiDetectionRequest.StudentSnapshot original = students.removeLast();
				students.add(new AiDetectionRequest.StudentSnapshot(
					original.studentRef(), original.classRef(), original.enrolledWeeks(), "returned",
					original.consent()
				));
			}
		}
		students.sort(Comparator.comparing(AiDetectionRequest.StudentSnapshot::studentRef));

		List<AiDetectionRequest.LearningEventSnapshot> events = ordered.stream()
			.filter(record -> aliasByStudent.containsKey(record.studentId()))
			.map(record -> new AiDetectionRequest.LearningEventSnapshot(
				"le_" + compact(record.id()), aliasByStudent.get(record.studentId()),
				record.recordType().aiValue(), record.occurredAt().atOffset(ZoneOffset.UTC),
				record.correct(), record.durationSec(), record.passageWordCount(),
				record.areaTag(), record.subjectTrack(), record.typeTag(), record.itemFormat(),
				record.assignmentTitleText(), record.sourceType()))
			.toList();
		List<AiDetectionRequest.ClassReference> classes = students.stream()
			.map(AiDetectionRequest.StudentSnapshot::classRef)
			.filter(classRef -> !"cl_unassigned".equals(classRef)).distinct()
			.map(AiDetectionRequest.ClassReference::new)
			.sorted(Comparator.comparing(AiDetectionRequest.ClassReference::classRef)).toList();
		List<AiDetectionRequest.DetectionEvidence> evidence = buildDetectionEvidence(
			teacherId, firstEvidenceWeek, weekStart, activityRecords, aliasByStudent,
			transitions
		);
		List<AiDetectionRequest.AlertContext> alertContext = alertContexts
			.latestByStudentAndSignalType(teacherId).stream()
			.filter(history -> aliasByStudent.containsKey(history.studentId()))
			.map(history -> new AiDetectionRequest.AlertContext(
				aliasByStudent.get(history.studentId()), history.signalType(), history.status(),
				history.resolvedAt() == null ? null
					: history.resolvedAt().atOffset(ZoneOffset.UTC),
				history.followedUp()
			))
			.sorted(Comparator.comparing(AiDetectionRequest.AlertContext::studentRef)
				.thenComparing(AiDetectionRequest.AlertContext::signalType))
			.toList();
		// Deterministic ordering is required because snapshot_hash represents data,
		// not the unspecified row order returned by JPA or PostgreSQL.
		return new AiDetectionRequest(new AiDetectionRequest.SnapshotMeta(
			weekStart, null, termContext.trim(), classes), students, events, alertContext, evidence);
	}

	private List<AiDetectionRequest.DetectionEvidence> buildDetectionEvidence(
		UUID teacherId,
		LocalDate firstWeek,
		LocalDate analysisWeek,
		List<LearningRecord> activityRecords,
		Map<UUID, String> aliasByStudent,
		List<StudentStatusTransition> transitions
	) {
		Map<EvidenceWeek, AssignmentWeekSummary> assignments = new LinkedHashMap<>();
		for (AssignmentWeekSummary summary : evidenceProjections.findAssignmentSummaries(
			teacherId, firstWeek, analysisWeek)) {
			if (aliasByStudent.containsKey(summary.studentId())) {
				assignments.put(new EvidenceWeek(summary.studentId(), summary.weekStart()), summary);
			}
		}
		Map<EvidenceWeek, Integer> activities = new LinkedHashMap<>();
		for (LearningRecord record : activityRecords) {
			if (!aliasByStudent.containsKey(record.studentId())) continue;
			LocalDate recordWeek = mondayOf(record.occurredAt());
			if (!recordWeek.isBefore(firstWeek) && !recordWeek.isAfter(analysisWeek)) {
				activities.merge(new EvidenceWeek(record.studentId(), recordWeek), 1, Integer::sum);
			}
		}

		List<AiDetectionRequest.DetectionEvidence> evidence = new ArrayList<>();
		for (var entry : aliasByStudent.entrySet()) {
			UUID studentId = entry.getKey();
			String studentRef = entry.getValue();
			for (int index = 0; index < EVIDENCE_WEEK_COUNT; index++) {
				LocalDate currentWeek = firstWeek.plusWeeks(index);
				AssignmentWeekSummary summary = assignments.get(
					new EvidenceWeek(studentId, currentWeek)
				);
				int expected = summary == null ? 0 : summary.expectedCount();
				int submitted = summary == null ? 0 : summary.submittedCount();
				evidence.add(AiDetectionRequest.DetectionEvidence.assignmentWindow(
					ASSIGNMENT_WEEK_SUMMARY,
					"assignment-summary:" + studentRef + ":" + currentWeek,
					studentRef, currentWeek, expected, submitted
				));
				evidence.add(AiDetectionRequest.DetectionEvidence.weeklyActivity(
					STUDENT_WEEK_ACTIVITY,
					"activity-summary:" + studentRef + ":" + currentWeek,
					studentRef, currentWeek,
					activities.getOrDefault(new EvidenceWeek(studentId, currentWeek), 0)
				));
			}
		}
		for (StudentStatusTransition transition : transitions) {
			String studentRef = aliasByStudent.get(transition.studentId());
			if (studentRef == null || !"paused".equals(transition.fromStatus())
				|| !"returned".equals(transition.toStatus())) continue;
			var occurredAt = transition.occurredAt().atZone(SERVICE_ZONE).toOffsetDateTime();
			evidence.add(AiDetectionRequest.DetectionEvidence.enrollmentTransition(
				STUDENT_STATUS_HISTORY,
				"status-history:" + studentRef + ":"
					+ DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(occurredAt),
				studentRef, occurredAt, transition.fromStatus(), transition.toStatus()
			));
		}
		evidence.sort(EVIDENCE_ORDER);
		return List.copyOf(evidence);
	}

	private static LocalDate mondayOf(Instant instant) {
		return instant.atZone(SERVICE_ZONE).toLocalDate()
			.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	private static String evidenceTime(AiDetectionRequest.DetectionEvidence evidence) {
		return evidence.weekStart() != null ? evidence.weekStart().toString()
			: DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(evidence.occurredAt());
	}

	private record EvidenceWeek(UUID studentId, LocalDate weekStart) {
	}

	private static String classRef(UUID id) {
		return id == null ? "cl_unassigned" : "cl_" + compact(id);
	}
	private static String compact(UUID id) { return id.toString().replace("-", ""); }
}
