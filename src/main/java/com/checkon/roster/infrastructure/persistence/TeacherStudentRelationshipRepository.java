package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.roster.domain.TeacherStudentRelationship;

public interface TeacherStudentRelationshipRepository
	extends JpaRepository<TeacherStudentRelationship, UUID> {

	boolean existsByStudentIdAndStatus(
		UUID studentId,
		com.checkon.roster.domain.RelationshipStatus status
	);
}

