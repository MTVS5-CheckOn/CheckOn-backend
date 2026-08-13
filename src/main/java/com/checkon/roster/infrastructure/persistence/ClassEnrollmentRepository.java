package com.checkon.roster.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.checkon.roster.domain.ClassEnrollment;
import com.checkon.roster.domain.RelationshipStatus;

public interface ClassEnrollmentRepository
	extends JpaRepository<ClassEnrollment, UUID> {

	Optional<ClassEnrollment> findByIdAndTeacherId(UUID id, UUID teacherId);

	List<ClassEnrollment> findAllByTeacherIdAndStatus(
		UUID teacherId,
		RelationshipStatus status
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select enrollment
		from ClassEnrollment enrollment
		where enrollment.teacherId = :teacherId
		  and enrollment.studentId = :studentId
		  and enrollment.status <> com.checkon.roster.domain.RelationshipStatus.ENDED
		""")
	Optional<ClassEnrollment> findCurrentForUpdate(
		@Param("teacherId") UUID teacherId,
		@Param("studentId") UUID studentId
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select enrollment
		from ClassEnrollment enrollment
		where enrollment.classGroupId = :classGroupId
		  and enrollment.teacherId = :teacherId
		  and enrollment.status = :status
		order by enrollment.id
		""")
	List<ClassEnrollment> findAllForUpdate(
		@Param("classGroupId") UUID classGroupId,
		@Param("teacherId") UUID teacherId,
		@Param("status") RelationshipStatus status
	);

	long countByClassGroupIdAndTeacherIdAndStatus(
		UUID classGroupId,
		UUID teacherId,
		RelationshipStatus status
	);

	/**
	 * 지정 시각에 학생이 해당 강사의 반에 소속되어 있었는지 확인한다.
	 * 입반 시각은 포함하고, 퇴반 시각부터는 더 이상 소속으로 보지 않는다.
	 */
	@Query("""
		select (count(enrollment) > 0)
		from ClassEnrollment enrollment
		where enrollment.classGroupId = :classGroupId
		  and enrollment.teacherId = :teacherId
		  and enrollment.studentId = :studentId
		  and enrollment.enrolledAt <= :occurredAt
		  and (enrollment.endedAt is null or enrollment.endedAt > :occurredAt)
		""")
	boolean existsAt(
		@Param("classGroupId") UUID classGroupId,
		@Param("teacherId") UUID teacherId,
		@Param("studentId") UUID studentId,
		@Param("occurredAt") Instant occurredAt
	);
}
