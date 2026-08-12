package com.checkon.detection.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "detection_signal_results")
public class DetectionSignalResult {

	private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
	private static final BigDecimal MAX_SCORE = BigDecimal.ONE;

	@Id
	private UUID id;

	@Column(name = "detection_run_id", nullable = false)
	private UUID detectionRunId;

	@Column(name = "external_signal_id", nullable = false, length = 120)
	private String externalSignalId;

	@Column(name = "student_ref", nullable = false, length = 80)
	private String studentRef;

	@Column(name = "class_ref", nullable = false, length = 80)
	private String classRef;

	@Column(name = "rule_id", nullable = false, length = 20)
	private String ruleId;

	@Column(name = "signal_type", nullable = false, length = 40)
	private String signalType;

	@Column(name = "display_label", nullable = false, length = 100)
	private String displayLabel;

	@Column(nullable = false, precision = 18, scale = 16)
	private BigDecimal score;

	@Column(nullable = false)
	private int rank;

	@Column(nullable = false)
	private boolean advisory;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DetectionLifecycle lifecycle;

	@Column(name = "brief_text", nullable = false, columnDefinition = "text")
	private String briefText;

	@Column(name = "gate_passed", nullable = false)
	private boolean gatePassed;

	@Column(name = "fallback_used", nullable = false)
	private boolean fallbackUsed;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@OneToMany(
		mappedBy = "detectionSignalResult",
		cascade = {CascadeType.PERSIST, CascadeType.MERGE},
		fetch = FetchType.LAZY
	)
	@OrderBy("createdAt ASC, id ASC")
	private List<DetectionResultEvidence> evidence = new ArrayList<>();

	protected DetectionSignalResult() {
	}

	private DetectionSignalResult(
		UUID id,
		UUID detectionRunId,
		String externalSignalId,
		String studentRef,
		String classRef,
		String ruleId,
		String signalType,
		String displayLabel,
		BigDecimal score,
		int rank,
		boolean advisory,
		DetectionLifecycle lifecycle,
		String briefText,
		boolean gatePassed,
		boolean fallbackUsed,
		List<DetectionResultEvidenceDraft> evidenceDrafts,
		Instant createdAt
	) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.detectionRunId = Objects.requireNonNull(
			detectionRunId,
			"detectionRunId must not be null"
		);
		this.externalSignalId = requireText(externalSignalId, "externalSignalId");
		this.studentRef = requireText(studentRef, "studentRef");
		this.classRef = requireText(classRef, "classRef");
		this.ruleId = requireText(ruleId, "ruleId");
		this.signalType = requireText(signalType, "signalType");
		this.displayLabel = requireText(displayLabel, "displayLabel");
		this.score = requireScore(score);
		if (rank < 1) {
			throw new IllegalArgumentException("rank must be at least 1");
		}
		this.rank = rank;
		this.advisory = advisory;
		this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
		this.briefText = requireText(briefText, "briefText");
		this.gatePassed = gatePassed;
		this.fallbackUsed = fallbackUsed;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		if (evidenceDrafts == null || evidenceDrafts.isEmpty()) {
			throw new IllegalArgumentException("signal result must have at least one evidence");
		}
		evidenceDrafts.stream()
			.map(draft -> new DetectionResultEvidence(this, draft, createdAt))
			.forEach(evidence::add);
	}

	public static DetectionSignalResult create(
		UUID id,
		UUID detectionRunId,
		String externalSignalId,
		String studentRef,
		String classRef,
		String ruleId,
		String signalType,
		String displayLabel,
		BigDecimal score,
		int rank,
		boolean advisory,
		DetectionLifecycle lifecycle,
		String briefText,
		boolean gatePassed,
		boolean fallbackUsed,
		List<DetectionResultEvidenceDraft> evidenceDrafts,
		Instant createdAt
	) {
		return new DetectionSignalResult(
			id,
			detectionRunId,
			externalSignalId,
			studentRef,
			classRef,
			ruleId,
			signalType,
			displayLabel,
			score,
			rank,
			advisory,
			lifecycle,
			briefText,
			gatePassed,
			fallbackUsed,
			evidenceDrafts,
			createdAt
		);
	}

	private static BigDecimal requireScore(BigDecimal score) {
		Objects.requireNonNull(score, "score must not be null");
		if (score.compareTo(MIN_SCORE) < 0 || score.compareTo(MAX_SCORE) > 0) {
			throw new IllegalArgumentException("score must be between 0 and 1");
		}
		return score;
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

	public UUID detectionRunId() {
		return detectionRunId;
	}

	public String externalSignalId() {
		return externalSignalId;
	}

	public String studentRef() {
		return studentRef;
	}

	public String classRef() {
		return classRef;
	}

	public String ruleId() {
		return ruleId;
	}

	public String signalType() {
		return signalType;
	}

	public String displayLabel() {
		return displayLabel;
	}

	public BigDecimal score() {
		return score;
	}

	public int rank() {
		return rank;
	}

	public boolean advisory() {
		return advisory;
	}

	public DetectionLifecycle lifecycle() {
		return lifecycle;
	}

	public String briefText() {
		return briefText;
	}

	public boolean gatePassed() {
		return gatePassed;
	}

	public boolean fallbackUsed() {
		return fallbackUsed;
	}

	public List<DetectionResultEvidence> evidence() {
		return Collections.unmodifiableList(evidence);
	}

	public Instant createdAt() {
		return createdAt;
	}
}
