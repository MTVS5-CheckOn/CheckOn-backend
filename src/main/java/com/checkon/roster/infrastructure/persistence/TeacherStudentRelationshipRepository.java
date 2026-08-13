package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.checkon.roster.domain.TeacherStudentRelationship;

public interface TeacherStudentRelationshipRepository
	extends JpaRepository<TeacherStudentRelationship, UUID> {

	boolean existsByStudentIdAndStatus(
		UUID studentId,
		com.checkon.roster.domain.RelationshipStatus status
	);

	boolean existsByTeacherIdAndStudentIdAndStatus(
		UUID teacherId,
		UUID studentId,
		com.checkon.roster.domain.RelationshipStatus status
	);

	List<TeacherStudentRelationship> findAllByTeacherIdAndStatus(
		UUID teacherId,
		com.checkon.roster.domain.RelationshipStatus status
	);

	List<TeacherStudentRelationship> findAllByTeacherIdAndStatusIn(
		UUID teacherId,
		List<com.checkon.roster.domain.RelationshipStatus> statuses
	);

	Optional<TeacherStudentRelationship> findByIdAndTeacherId(
		UUID id,
		UUID teacherId
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select relationship
		from TeacherStudentRelationship relationship
		where relationship.teacherId = :teacherId
		  and relationship.studentId = :studentId
		  and relationship.status <> com.checkon.roster.domain.RelationshipStatus.ENDED
		""")
	Optional<TeacherStudentRelationship> findCurrentForUpdate(
		@Param("teacherId") UUID teacherId,
		@Param("studentId") UUID studentId
	);
}
