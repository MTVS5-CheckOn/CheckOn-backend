package com.checkon.learning.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.learning.domain.LearningRecord;

public interface LearningRecordRepository extends JpaRepository<LearningRecord, UUID> {
	List<LearningRecord> findAllByTeacherIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
		UUID teacherId, Instant from, Instant to);
	List<LearningRecord> findAllByTeacherIdAndStudentIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
		UUID teacherId, UUID studentId, Instant from, Instant to);
	Optional<LearningRecord> findByIdAndTeacherId(UUID id, UUID teacherId);
}
