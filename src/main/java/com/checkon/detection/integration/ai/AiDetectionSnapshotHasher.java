package com.checkon.detection.integration.ai;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class AiDetectionSnapshotHasher {

	private static final String HASH_ALGORITHM = "SHA-256";
	private static final String HASH_PREFIX = "sha256:";

	private static final Comparator<AiDetectionRequest.ClassReference> CLASS_ORDER =
		Comparator.comparing(AiDetectionRequest.ClassReference::classRef);

	private static final Comparator<AiDetectionRequest.StudentSnapshot> STUDENT_ORDER =
		Comparator.comparing(AiDetectionRequest.StudentSnapshot::studentRef)
			.thenComparing(AiDetectionRequest.StudentSnapshot::classRef);

	private static final Comparator<AiDetectionRequest.LearningEventSnapshot> EVENT_ORDER =
		Comparator.comparing(AiDetectionRequest.LearningEventSnapshot::occurredAt)
			.thenComparing(AiDetectionRequest.LearningEventSnapshot::recordId);

	private static final Comparator<AiDetectionRequest.AlertContext> ALERT_ORDER =
		Comparator.comparing(AiDetectionRequest.AlertContext::studentRef)
			.thenComparing(AiDetectionRequest.AlertContext::signalType);

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
			byte[] canonicalJson = objectMapper.writeValueAsBytes(
				NormalizedRequest.from(request)
			);
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
		@JsonProperty("alert_context") List<AiDetectionRequest.AlertContext> alertContext
	) {

		private static NormalizedRequest from(AiDetectionRequest request) {
			Objects.requireNonNull(request.snapshotMeta(), "snapshotMeta must not be null");

			return new NormalizedRequest(
				NormalizedSnapshotMeta.from(request.snapshotMeta()),
				sorted(request.students(), STUDENT_ORDER, "students"),
				sorted(request.learningEvents(), EVENT_ORDER, "learningEvents"),
				sorted(request.alertContext(), ALERT_ORDER, "alertContext")
			);
		}
	}

	private record NormalizedSnapshotMeta(
		@JsonProperty("week_start") java.time.LocalDate weekStart,
		@JsonProperty("term_context") String termContext,
		List<AiDetectionRequest.ClassReference> classes
	) {

		private static NormalizedSnapshotMeta from(AiDetectionRequest.SnapshotMeta snapshotMeta) {
			return new NormalizedSnapshotMeta(
				snapshotMeta.weekStart(),
				snapshotMeta.termContext(),
				sorted(snapshotMeta.classes(), CLASS_ORDER, "classes")
			);
		}
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
