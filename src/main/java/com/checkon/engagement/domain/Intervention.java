package com.checkon.engagement.domain;

import java.time.Instant;
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
@Table(name = "interventions")
public class Intervention {
	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(name = "student_id", nullable = false)
	private UUID studentId;

	@Column(name = "alert_id", nullable = false)
	private UUID alertId;

	@Column(nullable = false, length = 40)
	private String type;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private InterventionStatus status;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Intervention() {
	}

	public static Intervention create(
		UUID teacherId,
		UUID studentId,
		UUID alertId,
		String type,
		String content,
		Instant now
	) {
		if (type == null || type.isBlank() || content == null || content.isBlank()) {
			throw new IllegalArgumentException("type and content are required");
		}
		Intervention intervention = new Intervention();
		intervention.teacherId = Objects.requireNonNull(teacherId);
		intervention.studentId = Objects.requireNonNull(studentId);
		intervention.alertId = Objects.requireNonNull(alertId);
		intervention.type = type.trim();
		intervention.content = content.trim();
		intervention.status = InterventionStatus.OPEN;
		intervention.createdAt = Objects.requireNonNull(now);
		intervention.updatedAt = now;
		return intervention;
	}

	public void complete(Instant now) {
		if (status == InterventionStatus.COMPLETED) {
			return;
		}
		if (status != InterventionStatus.OPEN) {
			throw new IllegalStateException("only open intervention can complete");
		}
		status = InterventionStatus.COMPLETED;
		completedAt = Objects.requireNonNull(now);
		updatedAt = now;
	}

	public void cancel(Instant now) {
		if (status == InterventionStatus.CANCELLED) {
			return;
		}
		if (status != InterventionStatus.OPEN) {
			throw new IllegalStateException("only open intervention can cancel");
		}
		status = InterventionStatus.CANCELLED;
		updatedAt = Objects.requireNonNull(now);
	}

	public UUID id() { return id; }
	public UUID teacherId() { return teacherId; }
	public UUID studentId() { return studentId; }
	public UUID alertId() { return alertId; }
	public String type() { return type; }
	public String content() { return content; }
	public InterventionStatus status() { return status; }
	public Instant createdAt() { return createdAt; }
}
