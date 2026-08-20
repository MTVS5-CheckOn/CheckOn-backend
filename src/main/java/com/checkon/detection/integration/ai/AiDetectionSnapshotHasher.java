package com.checkon.detection.integration.ai;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AiDetectionSnapshotHasher {

	private static final String HASH_ALGORITHM = "SHA-256";
	private static final String HASH_PREFIX = "sha256:";

	private static final Comparator<AiDetectionRequest.StudentSnapshot> STUDENT_ORDER =
		Comparator.comparing(AiDetectionRequest.StudentSnapshot::studentRef)
			.thenComparing(AiDetectionRequest.StudentSnapshot::classRef);

	private static final Comparator<AiDetectionRequest.LearningEventSnapshot> EVENT_ORDER =
		Comparator.comparing(AiDetectionRequest.LearningEventSnapshot::recordId);

	private static final Comparator<AiDetectionRequest.AlertContext> ALERT_ORDER =
		Comparator.comparing(AiDetectionRequest.AlertContext::studentRef)
			.thenComparing(AiDetectionRequest.AlertContext::signalType);

	private static final Comparator<AiDetectionRequest.DetectionEvidence> EVIDENCE_ORDER =
		Comparator.comparing(AiDetectionRequest.DetectionEvidence::kind)
			.thenComparing(AiDetectionRequest.DetectionEvidence::studentRef)
			.thenComparing(AiDetectionSnapshotHasher::evidenceAt)
			.thenComparing(AiDetectionRequest.DetectionEvidence::sourceTable)
			.thenComparing(AiDetectionRequest.DetectionEvidence::recordId);

	private final ObjectMapper objectMapper;

	public AiDetectionSnapshotHasher() {
		this(JsonMapper.builder()
			.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.findAndAddModules()
			.build());
	}

	AiDetectionSnapshotHasher(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String hash(AiDetectionRequest request) {
		Objects.requireNonNull(request, "request must not be null");

		try {
			byte[] canonicalJson = canonicalJson(request);
			byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM)
				.digest(canonicalJson);
			return HASH_PREFIX + HexFormat.of().formatHex(digest);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Failed to serialize AI detection snapshot", exception);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private record NormalizedRequest(
		@JsonProperty("snapshot_meta") NormalizedSnapshotMeta snapshotMeta,
		List<AiDetectionRequest.StudentSnapshot> students,
		@JsonProperty("learning_events")
		List<AiDetectionRequest.LearningEventSnapshot> learningEvents,
		@JsonProperty("alert_context") List<AiDetectionRequest.AlertContext> alertContext,
		@JsonProperty("detection_evidence")
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<NormalizedEvidence> detectionEvidence
	) {

		private static NormalizedRequest from(AiDetectionRequest request) {
			Objects.requireNonNull(request.snapshotMeta(), "snapshotMeta must not be null");

			return new NormalizedRequest(
				NormalizedSnapshotMeta.from(request.snapshotMeta()),
				sorted(request.students(), STUDENT_ORDER, "students"),
				sorted(request.learningEvents(), EVENT_ORDER, "learningEvents"),
				sorted(request.alertContext(), ALERT_ORDER, "alertContext"),
				sorted(request.detectionEvidence(), EVIDENCE_ORDER, "detectionEvidence")
					.stream().map(NormalizedEvidence::from).toList()
			);
		}
	}

	/** Package-visible only so the fixed AI contract vector can verify exact bytes. */
	byte[] canonicalJson(AiDetectionRequest request) throws JacksonException {
		JsonNode normalized = objectMapper.valueToTree(NormalizedRequest.from(request));
		return objectMapper.writeValueAsBytes(sortObjectKeys(normalized));
	}

	private record NormalizedSnapshotMeta(
		@JsonProperty("week_start") java.time.LocalDate weekStart,
		@JsonProperty("term_context") String termContext
	) {

		private static NormalizedSnapshotMeta from(AiDetectionRequest.SnapshotMeta snapshotMeta) {
			return new NormalizedSnapshotMeta(
				snapshotMeta.weekStart(),
				snapshotMeta.termContext()
			);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record NormalizedEvidence(
		String kind,
		@JsonProperty("source_table") String sourceTable,
		@JsonProperty("record_id") String recordId,
		@JsonProperty("student_ref") String studentRef,
		String at,
		@JsonProperty("expected_count") Integer expectedCount,
		@JsonProperty("submitted_count") Integer submittedCount,
		@JsonProperty("activity_count") Integer activityCount,
		@JsonProperty("enrolled_seconds") Long enrolledSeconds,
		@JsonProperty("from_status") String fromStatus,
		@JsonProperty("to_status") String toStatus
	) {
		private static NormalizedEvidence from(
			AiDetectionRequest.DetectionEvidence evidence
		) {
			return new NormalizedEvidence(
				evidence.kind(), evidence.sourceTable(), evidence.recordId(),
				evidence.studentRef(), evidenceAt(evidence), evidence.expectedCount(),
				evidence.submittedCount(), evidence.activityCount(), evidence.enrolledSeconds(),
				evidence.fromStatus(), evidence.toStatus()
			);
		}
	}

	private static String evidenceAt(AiDetectionRequest.DetectionEvidence evidence) {
		if (evidence.weekStart() != null) return evidence.weekStart().toString();
		if (evidence.occurredAt() != null) {
			return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(evidence.occurredAt());
		}
		throw new IllegalArgumentException("detection evidence must have weekStart or occurredAt");
	}

	private JsonNode sortObjectKeys(JsonNode node) {
		if (node.isObject()) {
			ObjectNode sorted = objectMapper.createObjectNode();
			TreeMap<String, JsonNode> fields = new TreeMap<>();
			node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
			fields.forEach((name, value) -> sorted.set(name, sortObjectKeys(value)));
			return sorted;
		}
		if (node.isArray()) {
			ArrayNode sorted = objectMapper.createArrayNode();
			node.forEach(value -> sorted.add(sortObjectKeys(value)));
			return sorted;
		}
		return node;
	}

	private static <T> List<T> sorted(
		List<T> values,
		Comparator<? super T> comparator,
		String fieldName
	) {
		Objects.requireNonNull(values, fieldName + " must not be null");
		return values.stream()
			.sorted(comparator)
			.toList();
	}
}
