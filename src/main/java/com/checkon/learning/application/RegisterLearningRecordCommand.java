package com.checkon.learning.application;

import java.time.Instant;
import java.util.UUID;

import com.checkon.learning.domain.LearningRecordType;

/** Application input shared by single-record and future batch ingestion paths. */
public record RegisterLearningRecordCommand(
	UUID studentId,
	UUID classGroupId,
	LearningRecordType recordType,
	Instant occurredAt,
	String sourceType,
	String externalRecordRef,
	Boolean correct,
	Integer durationSec,
	Integer passageWordCount,
	String areaTag,
	String subjectTrack,
	String typeTag,
	String itemFormat,
	String assignmentTitleText
) {
}
