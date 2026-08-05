package com.checkon.engagement.presentation;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.engagement.application.TodoService;
import com.checkon.engagement.application.TodoService.TodoResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/todos")
public class TodoController {
	private final TodoService todoService;
	public TodoController(TodoService todoService) { this.todoService = todoService; }

	@PatchMapping("/{todoId}")
	TodoResult complete(@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID todoId, @Valid @RequestBody TodoUpdateRequest request) {
		return todoService.complete(principal == null ? null : principal.teacherProfileId(),
			todoId, request.done());
	}

	public record TodoUpdateRequest(@NotNull Boolean done) {}
}

