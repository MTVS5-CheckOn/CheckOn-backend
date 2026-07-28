package com.checkon.detection.infrastructure.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.detection.domain.DetectionRun;

public interface DetectionRunRepository extends JpaRepository<DetectionRun, UUID> {

	@EntityGraph(attributePaths = "attempts")
	Optional<DetectionRun> findByTeacherIdAndAnalysisDate(
		UUID teacherId,
		LocalDate analysisDate
	);

	Optional<DetectionRun> findByIdAndTeacherId(UUID id, UUID teacherId);
}
