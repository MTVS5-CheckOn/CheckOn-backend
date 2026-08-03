package com.checkon.learning.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.application.PrepareDetectionRunService;
import com.checkon.detection.application.PrepareDetectionRunService.PreparedDetectionRun;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.infrastructure.persistence.LearningRecordRepository;
import com.checkon.roster.domain.ClassEnrollment;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;

/** Builds the minimum AI contract from backend-owned, pseudonymized records. */
@Service
public class LearningRecordSnapshotService {
	private static final Comparator<LearningRecord> RECORD_ORDER =
		Comparator.comparing(LearningRecord::occurredAt).thenComparing(LearningRecord::id);
	private final LearningRecordRepository records;
	private final ClassEnrollmentRepository enrollments;
	private final AiStudentAliasService aliases;
	private final PrepareDetectionRunService prepareService;
	private final TeacherTenantDatabaseContext tenantContext;

	public LearningRecordSnapshotService(LearningRecordRepository records,
		ClassEnrollmentRepository enrollments, AiStudentAliasService aliases,
		PrepareDetectionRunService prepareService, TeacherTenantDatabaseContext tenantContext) {
		this.records = records; this.enrollments = enrollments; this.aliases = aliases;
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
		Map<UUID, ClassEnrollment> activeEnrollments = new LinkedHashMap<>();
		for (ClassEnrollment enrollment : enrollments.findAllByTeacherIdAndStatus(
			teacherId, RelationshipStatus.ACTIVE)) {
			activeEnrollments.put(enrollment.studentId(), enrollment);
		}
		Map<UUID, String> aliasByStudent = new LinkedHashMap<>();
		for (LearningRecord record : ordered)
			aliasByStudent.computeIfAbsent(record.studentId(), id -> aliases.getOrCreate(teacherId, id));

		Map<UUID, LearningRecord> latestByStudent = new LinkedHashMap<>();
		for (LearningRecord record : ordered) latestByStudent.put(record.studentId(), record);
		List<AiDetectionRequest.StudentSnapshot> students = new ArrayList<>();
		for (var entry : aliasByStudent.entrySet()) {
			UUID studentId = entry.getKey();
			ClassEnrollment enrollment = activeEnrollments.get(studentId);
			UUID classId = enrollment == null
				? latestByStudent.get(studentId).classGroupId() : enrollment.classGroupId();
			int weeks = enrollment == null ? 0 : Math.max(0,
				(int) (Duration.between(enrollment.enrolledAt(), toExclusive).toDays() / 7));
			students.add(new AiDetectionRequest.StudentSnapshot(entry.getValue(),
				classRef(classId), weeks, enrollment == null ? "recorded" : "enrolled", "unknown"));
		}
		students.sort(Comparator.comparing(AiDetectionRequest.StudentSnapshot::studentRef));

		List<AiDetectionRequest.LearningEventSnapshot> events = ordered.stream()
			.map(record -> new AiDetectionRequest.LearningEventSnapshot(
				"le_" + compact(record.id()), aliasByStudent.get(record.studentId()),
				record.recordType().aiValue(), record.occurredAt().atOffset(ZoneOffset.UTC),
				record.correct(), record.durationSec(), record.passageWordCount(),
				record.areaTag(), record.subjectTrack(), record.typeTag(), record.itemFormat(),
				record.assignmentTitleText(), record.sourceType()))
			.toList();
		List<AiDetectionRequest.ClassReference> classes = ordered.stream()
			.map(LearningRecord::classGroupId).filter(Objects::nonNull).distinct()
			.map(id -> new AiDetectionRequest.ClassReference(classRef(id)))
			.sorted(Comparator.comparing(AiDetectionRequest.ClassReference::classRef)).toList();
		// Deterministic ordering is required because snapshot_hash represents data,
		// not the unspecified row order returned by JPA or PostgreSQL.
		return new AiDetectionRequest(new AiDetectionRequest.SnapshotMeta(
			weekStart, null, termContext.trim(), classes), students, events, List.of());
	}

	private static String classRef(UUID id) {
		return id == null ? "cl_unassigned" : "cl_" + compact(id);
	}
	private static String compact(UUID id) { return id.toString().replace("-", ""); }
}
