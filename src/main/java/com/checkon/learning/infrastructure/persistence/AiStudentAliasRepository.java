package com.checkon.learning.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.checkon.learning.domain.AiStudentAlias;

public interface AiStudentAliasRepository extends JpaRepository<AiStudentAlias, UUID> {
	Optional<AiStudentAlias> findByTeacherIdAndStudentId(UUID teacherId, UUID studentId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		INSERT INTO ai_student_aliases (teacher_id, student_id, alias, created_at)
		VALUES (:teacherId, :studentId, :alias, :createdAt)
		ON CONFLICT DO NOTHING
		""", nativeQuery = true)
	int insertIfAbsent(@Param("teacherId") UUID teacherId,
		@Param("studentId") UUID studentId, @Param("alias") String alias,
		@Param("createdAt") Instant createdAt);
}
