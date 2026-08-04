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
@Table(name = "engagement_alerts")
public class EngagementAlert {
	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(name = "student_id", nullable = false)
	private UUID studentId;

	@Column(name = "detection_signal_result_id", nullable = false)
	private UUID detectionSignalResultId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AlertStatus status;

	@Column(name = "decision_note", columnDefinition = "text")
	private String decisionNote;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected EngagementAlert() {
	}

	public static EngagementAlert pending(
		UUID teacherId,
		UUID studentId,
		UUID detectionSignalResultId,
		Instant now
	) {
		EngagementAlert alert = new EngagementAlert();
		alert.teacherId = Objects.requireNonNull(teacherId);
		alert.studentId = Objects.requireNonNull(studentId);
		alert.detectionSignalResultId = Objects.requireNonNull(detectionSignalResultId);
		alert.status = AlertStatus.PENDING_REVIEW;
		alert.createdAt = Objects.requireNonNull(now);
		alert.updatedAt = now;
		return alert;
	}

	public void approve(Instant now) {
		decide(AlertStatus.APPROVED, null, now);
	}

	public void reject(String note, Instant now) {
		if (note == null || note.isBlank()) {
			throw new IllegalArgumentException("rejection note must not be blank");
		}
		decide(AlertStatus.REJECTED, note.trim(), now);
	}

	private void decide(AlertStatus target, String note, Instant now) {
		// 동일 판단 재요청은 네트워크 재시도에 안전하게 성공시키되,
		// 강사의 최종 판단을 반대 상태로 덮어쓰지는 않는다.
		if (status == target) {
			return;
		}
		if (status != AlertStatus.PENDING_REVIEW) {
			throw new IllegalStateException("final alert decision cannot be changed");
		}
		status = target;
		decisionNote = note;
		decidedAt = Objects.requireNonNull(now);
		updatedAt = now;
	}

	public UUID id() { return id; }
	public UUID teacherId() { return teacherId; }
	public UUID studentId() { return studentId; }
	public UUID detectionSignalResultId() { return detectionSignalResultId; }
	public AlertStatus status() { return status; }
	public String decisionNote() { return decisionNote; }
	public Instant decidedAt() { return decidedAt; }
	public Instant createdAt() { return createdAt; }
}
