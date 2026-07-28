package com.checkon.detection.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "detection_result_evidence")
public class DetectionResultEvidence {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "detection_signal_result_id", nullable = false)
	private DetectionSignalResult detectionSignalResult;

	@Column(name = "source_hint", nullable = false, length = 80)
	private String sourceHint;

	@Column(name = "record_id", nullable = false, length = 120)
	private String recordId;

	@Column(nullable = false, columnDefinition = "text")
	private String summary;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected DetectionResultEvidence() {
	}

	DetectionResultEvidence(
		DetectionSignalResult detectionSignalResult,
		DetectionResultEvidenceDraft draft,
		Instant createdAt
	) {
		this.id = Objects.requireNonNull(draft.id(), "evidence id must not be null");
		this.detectionSignalResult = Objects.requireNonNull(
			detectionSignalResult,
			"detectionSignalResult must not be null"
		);
		this.sourceHint = requireText(draft.sourceHint(), "sourceHint");
		this.recordId = requireText(draft.recordId(), "recordId");
		this.summary = requireText(draft.summary(), "summary");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

	public UUID id() {
		return id;
	}

	public UUID detectionSignalResultId() {
		return detectionSignalResult.id();
	}

	public String sourceHint() {
		return sourceHint;
	}

	public String recordId() {
		return recordId;
	}

	public String summary() {
		return summary;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
