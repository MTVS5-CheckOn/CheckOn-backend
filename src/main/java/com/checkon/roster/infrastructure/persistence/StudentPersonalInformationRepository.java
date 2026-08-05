package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.roster.domain.StudentPersonalInformation;

public interface StudentPersonalInformationRepository
	extends JpaRepository<StudentPersonalInformation, UUID> {
}

