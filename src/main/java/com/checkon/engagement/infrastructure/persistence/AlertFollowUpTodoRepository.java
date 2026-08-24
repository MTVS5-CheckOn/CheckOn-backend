package com.checkon.engagement.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.engagement.domain.AlertFollowUpTodo;

public interface AlertFollowUpTodoRepository extends JpaRepository<AlertFollowUpTodo, UUID> {
	Optional<AlertFollowUpTodo> findByIdAndTeacherId(UUID id, UUID teacherId);
}
