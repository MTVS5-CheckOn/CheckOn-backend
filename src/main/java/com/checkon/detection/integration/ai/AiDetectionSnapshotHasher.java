package com.checkon.detection.integration.ai;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
	private static final DateTimeFormatter CANONICAL_SECONDS =
		DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
	private static final DateTimeFormatter CANONICAL_MICROSECONDS =
		new DateTimeFormatterBuilder()
			.appendPattern("uuuu-MM-dd'T'HH:mm:ss")
			.appendLiteral('.')
			.appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, false)
			.appendOffset("+HH:MM", "Z")
			.toFormatter(Locale.ROOT);

	private static final Comparator<AiDetectionRequest.StudentSnapshot> STUDENT_ORDER =
		Comparator.comparing(AiDetectionRequest.StudentSnapshot::studentRef);

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
		List<NormalizedLearningEvent> learningEvents,
		@JsonProperty("alert_context") List<NormalizedAlertContext> alertContext,
		@JsonProperty("detection_evidence")
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<NormalizedEvidence> detectionEvidence
	) {

		private static NormalizedRequest from(AiDetectionRequest request) {
			Objects.requireNonNull(request.snapshotMeta(), "snapshotMeta must not be null");

			return new NormalizedRequest(
				NormalizedSnapshotMeta.from(request.snapshotMeta()),
				sorted(request.students(), STUDENT_ORDER, "students"),
				sorted(request.learningEvents(), EVENT_ORDER, "learningEvents")
					.stream().map(NormalizedLearningEvent::from).toList(),
				sorted(request.alertContext(), ALERT_ORDER, "alertContext")
					.stream().map(NormalizedAlertContext::from).toList(),
				sorted(request.detectionEvidence(), EVIDENCE_ORDER, "detectionEvidence")
					.stream().map(NormalizedEvidence::from).toList()
			);
		}
	}

	private record NormalizedLearningEvent(
		@JsonProperty("record_id") String recordId,
		@JsonProperty("student_ref") String studentRef,
		String type,
		@JsonProperty("occurred_at") String occurredAt,
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
		private static NormalizedLearningEvent from(
			AiDetectionRequest.LearningEventSnapshot event
		) {
			return new NormalizedLearningEvent(
				event.recordId(), event.studentRef(), event.type(),
				formatCanonicalTimestamp(event.occurredAt()), event.correct(),
				event.durationSec(), event.passageWordCount(), event.areaTag(),
				event.subjectTrack(), event.typeTag(), event.itemFormat(),
				event.assignmentTitleText(), event.source()
			);
		}
	}

	private record NormalizedAlertContext(
		@JsonProperty("student_ref") String studentRef,
		@JsonProperty("signal_type") String signalType,
		String status,
		@JsonProperty("resolved_at") String resolvedAt,
		@JsonProperty("followed_up") boolean followedUp
	) {
		private static NormalizedAlertContext from(AiDetectionRequest.AlertContext alert) {
			return new NormalizedAlertContext(
				alert.studentRef(), alert.signalType(), alert.status(),
				alert.resolvedAt() == null
					? null : formatCanonicalTimestamp(alert.resolvedAt()),
				alert.followedUp()
			);
		}
	}

	/** Package-visible only so the fixed AI contract vector can verify exact bytes. */
	byte[] canonicalJson(AiDetectionRequest request) throws JacksonException {
		JsonNode normalized = objectMapper.valueToTree(NormalizedRequest.from(request));
		validateUnicodeScalars(normalized);
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
			return formatCanonicalTimestamp(evidence.occurredAt());
		}
		throw new IllegalArgumentException("detection evidence must have weekStart or occurredAt");
	}

	private static String formatCanonicalTimestamp(OffsetDateTime value) {
		Objects.requireNonNull(value, "canonical timestamp must not be null");
		int nanos = value.getNano();
		if (nanos % 1_000 != 0) {
			throw new IllegalArgumentException(
				"canonical timestamps must not exceed microsecond precision"
			);
		}
		return (nanos == 0 ? CANONICAL_SECONDS : CANONICAL_MICROSECONDS).format(value);
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

	private static void validateUnicodeScalars(JsonNode node) {
		if (node.isTextual()) {
			String value = node.asText();
			for (int index = 0; index < value.length(); index++) {
				char current = value.charAt(index);
				if (Character.isHighSurrogate(current)) {
					if (index + 1 >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index + 1))) {
						throw new IllegalArgumentException(
							"canonical strings must contain valid Unicode scalar values"
						);
					}
					index++;
				}
				else if (Character.isLowSurrogate(current)) {
					throw new IllegalArgumentException(
						"canonical strings must contain valid Unicode scalar values"
					);
				}
			}
			return;
		}
		node.forEach(AiDetectionSnapshotHasher::validateUnicodeScalars);
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
