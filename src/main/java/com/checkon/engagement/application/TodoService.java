package com.checkon.engagement.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.engagement.domain.AlertFollowUpTodo;
import com.checkon.engagement.domain.TodoKind;
import com.checkon.engagement.infrastructure.persistence.AlertFollowUpTodoRepository;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

@Service
public class TodoService {
	private final AlertFollowUpTodoRepository todos;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public TodoService(AlertFollowUpTodoRepository todos,
		TeacherTenantDatabaseContext tenantContext, Clock clock) {
		this.todos = todos; this.tenantContext = tenantContext; this.clock = clock;
	}

	@Transactional
	public TodoResult complete(UUID teacherId, UUID todoId, boolean done) {
		if (!done) throw TodoException.of(TodoException.Reason.INVALID_UPDATE, "only done=true is supported");
		if (teacherId == null) throw TodoException.of(TodoException.Reason.INVALID_PRINCIPAL, "teacher principal required");
		tenantContext.setCurrentTeacher(teacherId);
		AlertFollowUpTodo todo = todos.findByIdAndTeacherId(todoId, teacherId)
			.orElseThrow(() -> TodoException.of(TodoException.Reason.NOT_FOUND, "todo not found"));
		try { todo.complete(Instant.now(clock)); }
		catch (IllegalArgumentException exception) {
			throw TodoException.of(TodoException.Reason.INVALID_STATE, "invalid todo state");
		}
		return new TodoResult(todo.id(), TodoKind.ALERT_FOLLOW_UP, true, todo.completedAt());
	}

	public record TodoResult(UUID todoId, TodoKind kind, boolean done, Instant completedAt) {}
}

