package com.checkon.engagement.infrastructure.persistence;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;import com.checkon.engagement.domain.Intervention;
public interface InterventionRepository extends JpaRepository<Intervention,UUID>{Optional<Intervention> findByIdAndTeacherId(UUID id,UUID teacherId);}
