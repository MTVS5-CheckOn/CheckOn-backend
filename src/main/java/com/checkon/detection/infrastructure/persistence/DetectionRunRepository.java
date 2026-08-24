package com.checkon.detection.infrastructure.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.checkon.detection.domain.DetectionRun;

public interface DetectionRunRepository extends JpaRepository<DetectionRun, UUID> {

	@EntityGraph(attributePaths = "attempts")
	Optional<DetectionRun> findByTeacherIdAndAnalysisDate(
		UUID teacherId,
		LocalDate analysisDate
	);

	@EntityGraph(attributePaths = "attempts")
	Optional<DetectionRun> findByIdAndTeacherId(UUID id, UUID teacherId);

	@EntityGraph(attributePaths = "attempts")
	Optional<DetectionRun> findFirstByTeacherIdOrderByPreparedAtDescIdDesc(UUID teacherId);

	@EntityGraph(attributePaths = "attempts")
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT run FROM DetectionRun run WHERE run.id = :id AND run.teacherId = :teacherId")
	Optional<DetectionRun> findByIdAndTeacherIdForUpdate(
		@Param("id") UUID id,
		@Param("teacherId") UUID teacherId
	);
}
