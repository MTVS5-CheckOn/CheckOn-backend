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
@Table(name = "intervention_reminders")
public class InterventionReminder {
	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(name = "intervention_id", nullable = false)
	private UUID interventionId;

	@Column(name = "scheduled_at", nullable = false)
	private Instant scheduledAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReminderStatus status;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected InterventionReminder() {
	}

	public static InterventionReminder create(
		UUID teacherId,
		UUID interventionId,
		Instant scheduledAt,
		Instant now
	) {
		InterventionReminder reminder = new InterventionReminder();
		reminder.teacherId = Objects.requireNonNull(teacherId);
		reminder.interventionId = Objects.requireNonNull(interventionId);
		reminder.scheduledAt = Objects.requireNonNull(scheduledAt);
		reminder.status = ReminderStatus.ACTIVE;
		reminder.createdAt = Objects.requireNonNull(now);
		reminder.updatedAt = now;
		return reminder;
	}

	public void complete(Instant now) {
		finish(ReminderStatus.COMPLETED, now);
	}

	public void cancel(Instant now) {
		finish(ReminderStatus.CANCELLED, now);
	}

	private void finish(ReminderStatus target, Instant now) {
		if (status == target) {
			return;
		}
		if (status != ReminderStatus.ACTIVE) {
			throw new IllegalStateException("finished reminder cannot change state");
		}
		status = target;
		finishedAt = Objects.requireNonNull(now);
		updatedAt = now;
	}

	public UUID id() { return id; }
	public UUID teacherId() { return teacherId; }
	public UUID interventionId() { return interventionId; }
	public Instant scheduledAt() { return scheduledAt; }
	public ReminderStatus status() { return status; }
}
