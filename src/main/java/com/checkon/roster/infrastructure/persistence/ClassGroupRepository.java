package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.roster.domain.ClassGroup;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, UUID> {

	Optional<ClassGroup> findByIdAndTeacherId(UUID id, UUID teacherId);
}
