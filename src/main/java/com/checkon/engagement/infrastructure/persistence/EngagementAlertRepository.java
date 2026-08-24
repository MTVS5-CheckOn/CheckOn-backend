package com.checkon.engagement.infrastructure.persistence;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import com.checkon.engagement.domain.*;
public interface EngagementAlertRepository extends JpaRepository<EngagementAlert,UUID>{
 List<EngagementAlert> findAllByTeacherIdAndStatusOrderByCreatedAtAscIdAsc(UUID teacherId,AlertStatus status);
 Optional<EngagementAlert> findByIdAndTeacherId(UUID id,UUID teacherId);
}
