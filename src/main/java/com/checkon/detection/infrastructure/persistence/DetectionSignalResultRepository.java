package com.checkon.detection.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.detection.domain.DetectionSignalResult;

public interface DetectionSignalResultRepository
	extends JpaRepository<DetectionSignalResult, UUID> {

	@EntityGraph(attributePaths = "evidence")
	List<DetectionSignalResult> findAllByDetectionRunIdOrderByClassRefAscRankAsc(
		UUID detectionRunId
	);

	Optional<DetectionSignalResult> findByDetectionRunIdAndExternalSignalId(
		UUID detectionRunId,
		String externalSignalId
	);
}
