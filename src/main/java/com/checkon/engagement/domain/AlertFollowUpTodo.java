package com.checkon.engagement.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "alert_follow_up_todos")
public class AlertFollowUpTodo {
	@Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;
	@Column(name = "teacher_id", nullable = false) private UUID teacherId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private TodoKind kind;
	@Column(name = "alert_id", nullable = false) private UUID alertId;
	@Column(nullable = false, columnDefinition = "text") private String text;
	@Column(name = "due_date", nullable = false) private LocalDate dueDate;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private TodoStatus status;
	@Column(name = "completed_at") private Instant completedAt;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected AlertFollowUpTodo() {}

	public static AlertFollowUpTodo create(UUID teacherId, UUID alertId, String text,
		LocalDate dueDate, Instant now) {
		if (text == null || text.isBlank()) throw new IllegalArgumentException("todo text must not be blank");
		AlertFollowUpTodo todo = new AlertFollowUpTodo();
		todo.teacherId = Objects.requireNonNull(teacherId);
		todo.alertId = Objects.requireNonNull(alertId);
		todo.kind = TodoKind.ALERT_FOLLOW_UP;
		todo.text = text.trim();
		todo.dueDate = Objects.requireNonNull(dueDate);
		todo.status = TodoStatus.OPEN;
		todo.createdAt = Objects.requireNonNull(now);
		todo.updatedAt = now;
		return todo;
	}

	public void complete(Instant now) {
		if (status == TodoStatus.DONE) return;
		Objects.requireNonNull(now);
		if (now.isBefore(createdAt)) throw new IllegalArgumentException("completion precedes creation");
		status = TodoStatus.DONE; completedAt = now; updatedAt = now;
	}

	public UUID id() { return id; }
	public TodoKind kind() { return kind; }
	public TodoStatus status() { return status; }
	public Instant completedAt() { return completedAt; }
	public LocalDate dueDate() { return dueDate; }
	public String text() { return text; }
}

