package com.checkon.engagement.infrastructure.persistence;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.engagement.domain.InterventionReminder;
import com.checkon.engagement.domain.ReminderStatus;

public interface InterventionReminderRepository extends JpaRepository<InterventionReminder, UUID> {
	Optional<InterventionReminder> findByIdAndTeacherId(UUID id, UUID teacherId);
	Optional<InterventionReminder> findByTeacherIdAndInterventionIdAndStatus(
		UUID teacherId, UUID interventionId, ReminderStatus status
	);
}
