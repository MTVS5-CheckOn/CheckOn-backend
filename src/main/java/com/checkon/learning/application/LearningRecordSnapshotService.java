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
import com.checkon.detection.application.DetectionAssignmentWeekSummaryService;
import com.checkon.detection.application.DetectionAssignmentWeekSummaryService.AssignmentWeekSummary;
import com.checkon.detection.application.DetectionStudentStatusHistoryService;
import com.checkon.detection.application.DetectionStudentStatusHistoryService.PauseTransition;
import com.checkon.detection.application.DetectionStudentStatusHistoryService.ReturnedTransition;
import com.checkon.detection.application.PrepareDetectionRunService;
import com.checkon.detection.application.PrepareDetectionRunService.PreparedDetectionRun;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.AiDetectionSignalTypes;
import com.checkon.engagement.application.EngagementAlertContextService;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.infrastructure.persistence.LearningRecordRepository;
import com.checkon.roster.domain.ClassEnrollment;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.domain.TeacherStudentRelationship;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

/** Builds the minimum AI contract from backend-owned, pseudonymized records. */
@Service
public class LearningRecordSnapshotService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int EVIDENCE_WEEK_COUNT = 10;
	private static final String STUDENT_WEEK_ACTIVITY = "student_week_activity";
	private static final String ASSIGNMENT_WEEK_SUMMARY = "assignment_week_summary";
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
	private final EngagementAlertContextService alertContexts;
	private final AiStudentAliasService aliases;
	private final AiDetectionConsentPolicy consentPolicy;
	private final DetectionAssignmentWeekSummaryService assignmentSummaries;
	private final DetectionStudentStatusHistoryService statusHistory;
	private final PrepareDetectionRunService prepareService;
	private final TeacherTenantDatabaseContext tenantContext;

	public LearningRecordSnapshotService(LearningRecordRepository records,
		ClassEnrollmentRepository enrollments,
		TeacherStudentRelationshipRepository relationships,
		EngagementAlertContextService alertContexts,
		AiStudentAliasService aliases,
		AiDetectionConsentPolicy consentPolicy,
		DetectionAssignmentWeekSummaryService assignmentSummaries,
		DetectionStudentStatusHistoryService statusHistory,
		PrepareDetectionRunService prepareService, TeacherTenantDatabaseContext tenantContext) {
		this.records = records; this.enrollments = enrollments; this.aliases = aliases;
		this.relationships = relationships;
		this.alertContexts = alertContexts;
		this.consentPolicy = consentPolicy;
		this.assignmentSummaries = assignmentSummaries;
		this.statusHistory = statusHistory;
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
		Instant analysisWeekTo = weekStart.plusWeeks(1).atStartOfDay(SERVICE_ZONE).toInstant();
		Instant evidenceTo = toExclusive.isBefore(analysisWeekTo)
			? toExclusive : analysisWeekTo;
		List<LearningRecord> activityRecords = records
			.findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
				teacherId, evidenceFrom, evidenceTo)
			.stream().sorted(RECORD_ORDER).toList();

		// Absence can be a signal, so current students must not disappear merely
		// because they have no event in the exact ten-week learning-event range.
		Map<UUID, TeacherStudentRelationship> currentRelationships = new LinkedHashMap<>();
		relationships.findAllByTeacherIdAndStatusIn(
			teacherId, List.of(RelationshipStatus.ACTIVE, RelationshipStatus.PAUSED)
		)
			.stream()
			.sorted(Comparator.comparing(TeacherStudentRelationship::studentId))
			.forEach(relationship -> currentRelationships.put(
				relationship.studentId(), relationship
			));
		// Historical records remain backend-owned, but an ended relationship must
		// not make a former student part of the current AI request.
		List<UUID> snapshotStudentIds = List.copyOf(new TreeSet<>(currentRelationships.keySet()));
		Map<UUID, ClassEnrollment> currentEnrollments = new LinkedHashMap<>();
		for (ClassEnrollment enrollment : enrollments.findAllByTeacherIdAndStatusIn(
			teacherId, List.of(RelationshipStatus.ACTIVE, RelationshipStatus.PAUSED))) {
			currentEnrollments.put(enrollment.studentId(), enrollment);
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
		List<PauseTransition> pauseTransitions = currentRelationships.isEmpty()
			? List.of()
			: statusHistory.findPauseTransitions(
				teacherId,
				currentRelationships.values().stream()
					.map(TeacherStudentRelationship::startedAt)
					.min(Instant::compareTo)
					.orElseThrow(),
				toExclusive
			);
		Map<UUID, List<PauseInterval>> pausesByStudent = pauseIntervals(
			currentRelationships, pauseTransitions, toExclusive
		);
		Instant returnWeekFrom = weekStart.atStartOfDay(SERVICE_ZONE).toInstant();
		Instant returnWeekTo = weekStart.plusWeeks(1).atStartOfDay(SERVICE_ZONE).toInstant();
		List<ReturnedTransition> returnedTransitions = statusHistory
			.findReturnedTransitions(teacherId, returnWeekFrom, returnWeekTo)
			.stream()
			.filter(transition -> aliasByStudent.containsKey(transition.studentId()))
			.toList();
		List<AssignmentWeekSummary> assignmentWindows = assignmentSummaries
			.findAll(teacherId, firstEvidenceWeek, weekStart)
			.stream()
			.filter(summary -> aliasByStudent.containsKey(summary.studentId()))
			.toList();
		var returnedStudentIds = returnedTransitions.stream()
			.map(ReturnedTransition::studentId)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		// Weekly activity covers a wider evidence window than learning_events.
		// Validate provenance for every included record so an unknown source
		// cannot influence aggregate evidence while bypassing event mapping.
		activityRecords.stream()
			.filter(record -> aliasByStudent.containsKey(record.studentId()))
			.forEach(record -> AiLearningEventSourceMapper.toAiSource(record.sourceType()));

		Map<UUID, LearningRecord> latestByStudent = new LinkedHashMap<>();
		for (LearningRecord record : activityRecords) latestByStudent.put(record.studentId(), record);
		List<AiDetectionRequest.StudentSnapshot> students = new ArrayList<>();
		for (var entry : aliasByStudent.entrySet()) {
			UUID studentId = entry.getKey();
			TeacherStudentRelationship relationship = currentRelationships.get(studentId);
			ClassEnrollment enrollment = currentEnrollments.get(studentId);
			LearningRecord latestRecord = latestByStudent.get(studentId);
			UUID classId = enrollment != null ? enrollment.classGroupId()
				: latestRecord == null ? null : latestRecord.classGroupId();
			int weeks = enrolledWeeks(
				relationship.startedAt(), toExclusive,
				pausesByStudent.getOrDefault(studentId, List.of())
			);
			String status = relationship.status() == RelationshipStatus.PAUSED
				? "paused"
				: returnedStudentIds.contains(studentId) ? "returned" : "enrolled";
			students.add(new AiDetectionRequest.StudentSnapshot(entry.getValue(),
				classRef(classId), weeks, status,
				consentByStudent.get(studentId).requestConsent()));
		}
		students.sort(Comparator.comparing(AiDetectionRequest.StudentSnapshot::studentRef));

		List<AiDetectionRequest.LearningEventSnapshot> events = ordered.stream()
			.filter(record -> aliasByStudent.containsKey(record.studentId()))
			.map(record -> new AiDetectionRequest.LearningEventSnapshot(
				"le_" + compact(record.id()), aliasByStudent.get(record.studentId()),
				record.recordType().aiValue(), record.occurredAt().atOffset(ZoneOffset.UTC),
				record.correct(), record.durationSec(), record.passageWordCount(),
				record.areaTag(), record.subjectTrack(), record.typeTag(), record.itemFormat(),
				record.assignmentTitleText(),
				AiLearningEventSourceMapper.toAiSource(record.sourceType())))
			.toList();
		List<AiDetectionRequest.ClassReference> classes = students.stream()
			.map(AiDetectionRequest.StudentSnapshot::classRef)
			.filter(classRef -> !"cl_unassigned".equals(classRef)).distinct()
			.map(AiDetectionRequest.ClassReference::new)
			.sorted(Comparator.comparing(AiDetectionRequest.ClassReference::classRef)).toList();
		List<AiDetectionRequest.DetectionEvidence> evidence = buildDetectionEvidence(
			firstEvidenceWeek, weekStart, activityRecords, aliasByStudent,
			currentRelationships, pausesByStudent, toExclusive,
			assignmentWindows, returnedTransitions
		);
		List<AiDetectionRequest.AlertContext> alertContext = alertContexts
			.latestByStudentAndSignalType(teacherId).stream()
			.filter(history -> aliasByStudent.containsKey(history.studentId()))
			.filter(history -> AiDetectionSignalTypes.supports(history.signalType()))
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
		LocalDate firstWeek,
		LocalDate analysisWeek,
		List<LearningRecord> activityRecords,
		Map<UUID, String> aliasByStudent,
		Map<UUID, TeacherStudentRelationship> currentRelationships,
		Map<UUID, List<PauseInterval>> pausesByStudent,
		Instant toExclusive,
		List<AssignmentWeekSummary> assignmentWindows,
		List<ReturnedTransition> returnedTransitions
	) {
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
			LocalDate firstEligibleWeek = mondayOf(currentRelationships.get(studentId).startedAt());
			for (int index = 0; index < EVIDENCE_WEEK_COUNT; index++) {
				LocalDate currentWeek = firstWeek.plusWeeks(index);
				if (currentWeek.isBefore(firstEligibleWeek)) continue;
				long enrolledSeconds = enrolledSeconds(
					currentWeek, currentRelationships.get(studentId).startedAt(), toExclusive,
					pausesByStudent.getOrDefault(studentId, List.of())
				);
				if (enrolledSeconds == 0) continue;
				evidence.add(AiDetectionRequest.DetectionEvidence.weeklyActivity(
					STUDENT_WEEK_ACTIVITY,
					"activity-summary:" + studentRef + ":" + currentWeek,
					studentRef, currentWeek,
					activities.getOrDefault(new EvidenceWeek(studentId, currentWeek), 0),
					enrolledSeconds
				));
			}
		}
		for (AssignmentWeekSummary summary : assignmentWindows) {
			String studentRef = aliasByStudent.get(summary.studentId());
			if (studentRef == null) continue;
			evidence.add(AiDetectionRequest.DetectionEvidence.assignmentWindow(
				ASSIGNMENT_WEEK_SUMMARY,
				summary.recordId(),
				studentRef,
				summary.weekStart(),
				summary.expectedCount(),
				summary.submittedCount()
			));
		}
		for (ReturnedTransition transition : returnedTransitions) {
			String studentRef = aliasByStudent.get(transition.studentId());
			if (studentRef == null) continue;
			var occurredAt = transition.occurredAt().atZone(SERVICE_ZONE).toOffsetDateTime();
			evidence.add(AiDetectionRequest.DetectionEvidence.enrollmentTransition(
				STUDENT_STATUS_HISTORY,
				transition.id().toString(),
				studentRef, occurredAt, transition.fromStatus(), transition.toStatus()
			));
		}
		evidence.sort(EVIDENCE_ORDER);
		return List.copyOf(evidence);
	}

	private static Map<UUID, List<PauseInterval>> pauseIntervals(
		Map<UUID, TeacherStudentRelationship> currentRelationships,
		List<PauseTransition> transitions,
		Instant toExclusive
	) {
		Map<UUID, List<PauseTransition>> transitionsByStudent = new LinkedHashMap<>();
		for (PauseTransition transition : transitions) {
			if (!currentRelationships.containsKey(transition.studentId())) continue;
			transitionsByStudent.computeIfAbsent(
				transition.studentId(), ignored -> new ArrayList<>()
			).add(transition);
		}

		Map<UUID, List<PauseInterval>> result = new LinkedHashMap<>();
		for (var entry : currentRelationships.entrySet()) {
			Instant relationshipStartedAt = entry.getValue().startedAt();
			Instant pausedAt = null;
			List<PauseInterval> intervals = new ArrayList<>();
			for (PauseTransition transition : transitionsByStudent.getOrDefault(
				entry.getKey(), List.of()
			)) {
				if (transition.occurredAt().isBefore(relationshipStartedAt)) continue;
				if ("paused".equals(transition.toStatus())) {
					if (pausedAt == null) pausedAt = transition.occurredAt();
				}
				else if ("returned".equals(transition.toStatus()) && pausedAt != null) {
					Instant returnedAt = transition.occurredAt().isAfter(toExclusive)
						? toExclusive : transition.occurredAt();
					if (pausedAt.isBefore(returnedAt)) {
						intervals.add(new PauseInterval(pausedAt, returnedAt));
					}
					pausedAt = null;
				}
			}
			if (pausedAt != null && pausedAt.isBefore(toExclusive)) {
				intervals.add(new PauseInterval(pausedAt, toExclusive));
			}
			result.put(entry.getKey(), List.copyOf(intervals));
		}
		return Map.copyOf(result);
	}

	private static int enrolledWeeks(
		Instant relationshipStartedAt,
		Instant toExclusive,
		List<PauseInterval> pauses
	) {
		if (!relationshipStartedAt.isBefore(toExclusive)) return 0;
		Duration activeDuration = Duration.between(relationshipStartedAt, toExclusive);
		for (PauseInterval pause : pauses) {
			activeDuration = activeDuration.minus(Duration.between(pause.from(), pause.to()));
		}
		return Math.max(0, (int) (activeDuration.toDays() / 7));
	}

	private static long enrolledSeconds(
		LocalDate weekStart,
		Instant relationshipStartedAt,
		Instant toExclusive,
		List<PauseInterval> pauses
	) {
		Instant weekFrom = weekStart.atStartOfDay(SERVICE_ZONE).toInstant();
		Instant weekTo = weekStart.plusWeeks(1).atStartOfDay(SERVICE_ZONE).toInstant();
		Instant activeFrom = relationshipStartedAt.isAfter(weekFrom)
			? relationshipStartedAt : weekFrom;
		Instant activeTo = toExclusive.isBefore(weekTo) ? toExclusive : weekTo;
		if (!activeFrom.isBefore(activeTo)) return 0;

		long seconds = Duration.between(activeFrom, activeTo).getSeconds();
		for (PauseInterval pause : pauses) {
			Instant overlapFrom = pause.from().isAfter(activeFrom) ? pause.from() : activeFrom;
			Instant overlapTo = pause.to().isBefore(activeTo) ? pause.to() : activeTo;
			if (overlapFrom.isBefore(overlapTo)) {
				seconds -= Duration.between(overlapFrom, overlapTo).getSeconds();
			}
		}
		return Math.max(0, seconds);
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

	private record PauseInterval(Instant from, Instant to) {
	}

	private static String classRef(UUID id) {
		return id == null ? "cl_unassigned" : "cl_" + compact(id);
	}
	private static String compact(UUID id) { return id.toString().replace("-", ""); }
}
