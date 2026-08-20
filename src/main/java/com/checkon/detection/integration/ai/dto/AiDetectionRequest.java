package com.checkon.detection.integration.ai.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AiDetectionRequest(
	@JsonProperty("snapshot_meta") SnapshotMeta snapshotMeta,
	List<StudentSnapshot> students,
	@JsonProperty("learning_events") List<LearningEventSnapshot> learningEvents,
	@JsonProperty("alert_context") List<AlertContext> alertContext,
	@JsonProperty("detection_evidence") List<DetectionEvidence> detectionEvidence
) {
	public AiDetectionRequest {
		// v0.1 snapshots did not have this field. Treat omission and [] as the
		// same value so stored legacy snapshots can still be read and hashed.
		detectionEvidence = detectionEvidence == null ? List.of() : List.copyOf(detectionEvidence);
	}

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

	/**
	 * A pseudonymized, AI-citable fact that can prove absence (zero activity or
	 * no submission) as well as an actual state transition. The source-table
	 * values are stable logical names, not physical PostgreSQL table names.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DetectionEvidence(
		String kind,
		@JsonProperty("source_table") String sourceTable,
		@JsonProperty("record_id") String recordId,
		@JsonProperty("student_ref") String studentRef,
		@JsonProperty("week_start") LocalDate weekStart,
		@JsonProperty("expected_count") Integer expectedCount,
		@JsonProperty("submitted_count") Integer submittedCount,
		@JsonProperty("activity_count") Integer activityCount,
		@JsonProperty("enrolled_seconds") Long enrolledSeconds,
		@JsonProperty("occurred_at") OffsetDateTime occurredAt,
		@JsonProperty("from_status") String fromStatus,
		@JsonProperty("to_status") String toStatus
	) {
		public static DetectionEvidence assignmentWindow(
			String sourceTable,
			String recordId,
			String studentRef,
			LocalDate weekStart,
			int expectedCount,
			int submittedCount
		) {
			return new DetectionEvidence(
				"assignment_window", sourceTable, recordId, studentRef, weekStart,
				expectedCount, submittedCount, null, null, null, null, null
			);
		}

		public static DetectionEvidence weeklyActivity(
			String sourceTable,
			String recordId,
			String studentRef,
			LocalDate weekStart,
			int activityCount,
			long enrolledSeconds
		) {
			return new DetectionEvidence(
				"weekly_activity", sourceTable, recordId, studentRef, weekStart,
				null, null, activityCount, enrolledSeconds, null, null, null
			);
		}

		public static DetectionEvidence enrollmentTransition(
			String sourceTable,
			String recordId,
			String studentRef,
			OffsetDateTime occurredAt,
			String fromStatus,
			String toStatus
		) {
			return new DetectionEvidence(
				"enrollment_transition", sourceTable, recordId, studentRef, null,
				null, null, null, null, occurredAt, fromStatus, toStatus
			);
		}
	}
}
