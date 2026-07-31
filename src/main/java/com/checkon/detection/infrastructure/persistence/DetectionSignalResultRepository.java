package com.checkon.detection.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.checkon.detection.domain.DetectionSignalResult;

public interface DetectionSignalResultRepository
	extends JpaRepository<DetectionSignalResult, UUID> {

	@EntityGraph(attributePaths = "evidence")
	@Query("""
		SELECT signal
		FROM DetectionSignalResult signal, DetectionRun run
		WHERE signal.detectionRunId = run.id
		  AND run.id = :detectionRunId
		  AND run.teacherId = :teacherId
		ORDER BY signal.classRef ASC, signal.rank ASC
		""")
	List<DetectionSignalResult>
		findAllByDetectionRunIdAndDetectionRunTeacherIdOrderByClassRefAscRankAsc(
		@Param("detectionRunId") UUID detectionRunId,
		@Param("teacherId") UUID teacherId
	);

	@Query("""
		SELECT signal
		FROM DetectionSignalResult signal, DetectionRun run
		WHERE signal.detectionRunId = run.id
		  AND run.id = :detectionRunId
		  AND run.teacherId = :teacherId
		  AND signal.externalSignalId = :externalSignalId
		""")
	Optional<DetectionSignalResult>
		findByDetectionRunIdAndDetectionRunTeacherIdAndExternalSignalId(
		@Param("detectionRunId") UUID detectionRunId,
		@Param("teacherId") UUID teacherId,
		@Param("externalSignalId") String externalSignalId
	);
}
