package com.checkon.engagement.infrastructure.persistence;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;import com.checkon.engagement.domain.InterventionReminder;
public interface InterventionReminderRepository extends JpaRepository<InterventionReminder,UUID>{Optional<InterventionReminder> findByIdAndTeacherId(UUID id,UUID teacherId);boolean existsByTeacherIdAndInterventionIdAndStatus(UUID teacherId,UUID interventionId,com.checkon.engagement.domain.ReminderStatus status);}
