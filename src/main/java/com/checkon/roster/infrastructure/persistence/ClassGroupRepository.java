package com.checkon.roster.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.checkon.roster.domain.ClassGroup;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, UUID> {

	Optional<ClassGroup> findByIdAndTeacherId(UUID id, UUID teacherId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select classGroup
		from ClassGroup classGroup
		where classGroup.id = :classGroupId
		  and classGroup.teacherId = :teacherId
		""")
	Optional<ClassGroup> findByIdAndTeacherIdForUpdate(
		@Param("classGroupId") UUID classGroupId,
		@Param("teacherId") UUID teacherId
	);
}
