package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.roster.domain.ClassEnrollment;

public interface ClassEnrollmentRepository
	extends JpaRepository<ClassEnrollment, UUID> {

	Optional<ClassEnrollment> findByIdAndTeacherId(UUID id, UUID teacherId);

	List<ClassEnrollment> findAllByTeacherIdAndStatus(
		UUID teacherId,
		com.checkon.roster.domain.RelationshipStatus status
	);
}
