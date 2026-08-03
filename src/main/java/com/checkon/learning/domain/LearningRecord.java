package com.checkon.learning.domain;

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

/** Backend-owned source record from which an AI-safe snapshot is derived. */
@Entity
@Table(name = "learning_records")
public class LearningRecord {
	@Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;
	@Column(name = "teacher_id", nullable = false) private UUID teacherId;
	@Column(name = "student_id", nullable = false) private UUID studentId;
	@Column(name = "class_group_id") private UUID classGroupId;
	@Enumerated(EnumType.STRING) @Column(name = "record_type", nullable = false, length = 20)
	private LearningRecordType recordType;
	@Column(name = "occurred_at", nullable = false) private Instant occurredAt;
	@Column(name = "source_type", nullable = false, length = 80) private String sourceType;
	@Column(name = "external_record_ref", length = 255) private String externalRecordRef;
	private Boolean correct;
	@Column(name = "duration_sec") private Integer durationSec;
	@Column(name = "passage_word_count") private Integer passageWordCount;
	@Column(name = "area_tag", length = 80) private String areaTag;
	@Column(name = "subject_track", length = 80) private String subjectTrack;
	@Column(name = "type_tag", length = 80) private String typeTag;
	@Column(name = "item_format", length = 80) private String itemFormat;
	@Column(name = "assignment_title_text", length = 255) private String assignmentTitleText;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected LearningRecord() { }

	private LearningRecord(Draft draft, Instant now) {
		teacherId = Objects.requireNonNull(draft.teacherId(), "teacherId must not be null");
		studentId = Objects.requireNonNull(draft.studentId(), "studentId must not be null");
		classGroupId = draft.classGroupId();
		recordType = Objects.requireNonNull(draft.recordType(), "recordType must not be null");
		occurredAt = Objects.requireNonNull(draft.occurredAt(), "occurredAt must not be null");
		sourceType = requiredText(draft.sourceType(), "sourceType", 80);
		// The upstream reference is not an idempotency key. Blank means absent and
		// equal non-null values intentionally remain valid on different rows.
		externalRecordRef = optionalText(draft.externalRecordRef(), 255);
		correct = draft.correct();
		durationSec = nonNegative(draft.durationSec(), "durationSec");
		passageWordCount = nonNegative(draft.passageWordCount(), "passageWordCount");
		areaTag = optionalText(draft.areaTag(), 80);
		subjectTrack = optionalText(draft.subjectTrack(), 80);
		typeTag = optionalText(draft.typeTag(), 80);
		itemFormat = optionalText(draft.itemFormat(), 80);
		assignmentTitleText = optionalText(draft.assignmentTitleText(), 255);
		createdAt = Objects.requireNonNull(now, "now must not be null");
		updatedAt = now;
	}

	public static LearningRecord create(Draft draft, Instant now) {
		return new LearningRecord(Objects.requireNonNull(draft, "draft must not be null"), now);
	}

	private static Integer nonNegative(Integer value, String field) {
		if (value != null && value < 0) throw new IllegalArgumentException(field + " must not be negative");
		return value;
	}
	private static String requiredText(String value, String field, int max) {
		String normalized = optionalText(value, max);
		if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
		return normalized;
	}
	private static String optionalText(String value, int max) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.trim();
		if (normalized.length() > max) throw new IllegalArgumentException("text must not exceed " + max + " characters");
		return normalized;
	}

	public UUID id() { return id; }
	public UUID teacherId() { return teacherId; }
	public UUID studentId() { return studentId; }
	public UUID classGroupId() { return classGroupId; }
	public LearningRecordType recordType() { return recordType; }
	public Instant occurredAt() { return occurredAt; }
	public String sourceType() { return sourceType; }
	public String externalRecordRef() { return externalRecordRef; }
	public Boolean correct() { return correct; }
	public Integer durationSec() { return durationSec; }
	public Integer passageWordCount() { return passageWordCount; }
	public String areaTag() { return areaTag; }
	public String subjectTrack() { return subjectTrack; }
	public String typeTag() { return typeTag; }
	public String itemFormat() { return itemFormat; }
	public String assignmentTitleText() { return assignmentTitleText; }
	public Instant updatedAt() { return updatedAt; }

	public record Draft(UUID teacherId, UUID studentId, UUID classGroupId,
		LearningRecordType recordType, Instant occurredAt, String sourceType,
		String externalRecordRef, Boolean correct, Integer durationSec,
		Integer passageWordCount, String areaTag, String subjectTrack,
		String typeTag, String itemFormat, String assignmentTitleText) { }
}
