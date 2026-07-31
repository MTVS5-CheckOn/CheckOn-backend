package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.roster.domain.ClassEnrollment;

public interface ClassEnrollmentRepository
	extends JpaRepository<ClassEnrollment, UUID> {
}
