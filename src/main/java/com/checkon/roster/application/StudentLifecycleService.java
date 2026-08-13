package com.checkon.roster.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.detection.application.DetectionStudentStatusHistoryService;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.domain.TeacherStudentRelationship;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

@Service
public class StudentLifecycleService {

	private final TeacherStudentRelationshipRepository relationships;
	private final ClassEnrollmentRepository enrollments;
	private final DetectionStudentStatusHistoryService statusHistory;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public StudentLifecycleService(
		TeacherStudentRelationshipRepository relationships,
		ClassEnrollmentRepository enrollments,
		DetectionStudentStatusHistoryService statusHistory,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.relationships = relationships;
		this.enrollments = enrollments;
		this.statusHistory = statusHistory;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional
	public StudentLifecycleView pause(AuthenticatedAccount principal, UUID studentId) {
		UUID teacherId = setTenantScope(principal);
		TeacherStudentRelationship relationship = requireCurrent(teacherId, studentId);
		if (relationship.status() != RelationshipStatus.ACTIVE) {
			throw invalidState();
		}
		Instant occurredAt = clock.instant();
		try {
			enrollments.findCurrentForUpdate(teacherId, studentId).ifPresent(enrollment -> {
				enrollment.pause();
				enrollments.flush();
			});
			relationship.pause();
			relationships.flush();
			statusHistory.record(teacherId, studentId, occurredAt, "enrolled", "paused");
			return new StudentLifecycleView(studentId, RelationshipStatus.PAUSED, occurredAt);
		}
		catch (IllegalStateException exception) {
			throw invalidState();
		}
	}

	@Transactional
	public StudentLifecycleView resume(AuthenticatedAccount principal, UUID studentId) {
		UUID teacherId = setTenantScope(principal);
		TeacherStudentRelationship relationship = requireCurrent(teacherId, studentId);
		if (relationship.status() != RelationshipStatus.PAUSED) {
			throw invalidState();
		}
		Instant occurredAt = clock.instant();
		try {
			relationship.resume();
			relationships.flush();
			enrollments.findCurrentForUpdate(teacherId, studentId).ifPresent(enrollment -> {
				enrollment.resume();
				enrollments.flush();
			});
			statusHistory.record(teacherId, studentId, occurredAt, "paused", "returned");
			return new StudentLifecycleView(studentId, RelationshipStatus.ACTIVE, occurredAt);
		}
		catch (IllegalStateException exception) {
			throw invalidState();
		}
	}

	private UUID setTenantScope(AuthenticatedAccount principal) {
		if (principal == null || principal.role() != AccountRole.TEACHER
			|| principal.teacherProfileId() == null) {
			throw StudentLifecycleException.of(
				StudentLifecycleException.Reason.INVALID_PRINCIPAL,
				"valid teacher principal is required"
			);
		}
		tenantContext.setCurrentTeacher(principal.teacherProfileId());
		return principal.teacherProfileId();
	}

	private TeacherStudentRelationship requireCurrent(UUID teacherId, UUID studentId) {
		if (studentId == null) {
			throw notFound();
		}
		return relationships.findCurrentForUpdate(teacherId, studentId)
			.orElseThrow(StudentLifecycleService::notFound);
	}

	private static StudentLifecycleException notFound() {
		return StudentLifecycleException.of(
			StudentLifecycleException.Reason.NOT_FOUND, "student relationship not found"
		);
	}

	private static StudentLifecycleException invalidState() {
		return StudentLifecycleException.of(
			StudentLifecycleException.Reason.INVALID_STATE,
			"student relationship cannot transition from current state"
		);
	}

	public record StudentLifecycleView(
		UUID studentId,
		RelationshipStatus status,
		Instant occurredAt
	) {
	}
}
