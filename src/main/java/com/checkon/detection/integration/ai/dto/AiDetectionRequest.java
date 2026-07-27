package com.checkon.detection.integration.ai.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiDetectionRequest(
	@JsonProperty("snapshot_meta") SnapshotMeta snapshotMeta,
	List<StudentSnapshot> students,
	@JsonProperty("learning_events") List<LearningEventSnapshot> learningEvents,
	@JsonProperty("alert_context") List<AlertContext> alertContext
) {

	public record SnapshotMeta(
		@JsonProperty("week_start") LocalDate weekStart,
		@JsonProperty("snapshot_hash") String snapshotHash,
		@JsonProperty("term_context") String termContext,
		List<ClassReference> classes
	) {
	}

	public record ClassReference(
		@JsonProperty("class_ref") String classRef
	) {
	}

	public record StudentSnapshot(
		@JsonProperty("student_ref") String studentRef,
		@JsonProperty("class_ref") String classRef,
		@JsonProperty("enrolled_weeks") int enrolledWeeks,
		String status,
		String consent
	) {
	}

	public record LearningEventSnapshot(
		@JsonProperty("record_id") String recordId,
		@JsonProperty("student_ref") String studentRef,
		String type,
		@JsonProperty("occurred_at") OffsetDateTime occurredAt,
		Boolean correct,
		@JsonProperty("duration_sec") Integer durationSec,
		@JsonProperty("passage_word_count") Integer passageWordCount,
		@JsonProperty("area_tag") String areaTag,
		@JsonProperty("subject_track") String subjectTrack,
		@JsonProperty("type_tag") String typeTag,
		@JsonProperty("item_format") String itemFormat,
		@JsonProperty("assignment_title_text") String assignmentTitleText,
		String source
	) {
	}

	public record AlertContext(
		@JsonProperty("student_ref") String studentRef,
		@JsonProperty("signal_type") String signalType,
		String status,
		@JsonProperty("resolved_at") OffsetDateTime resolvedAt,
		@JsonProperty("followed_up") boolean followedUp
	) {
	}
}
