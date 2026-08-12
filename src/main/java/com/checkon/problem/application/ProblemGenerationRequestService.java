package com.checkon.problem.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.application.AiStudentAliasService;
import com.checkon.problem.domain.ProblemTargetKind;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository.NewOutboxEvent;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRequestRepository;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRequestRepository.NewRequest;
import com.checkon.roster.domain.ClassGroupStatus;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.infrastructure.persistence.ClassGroupRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProblemGenerationRequestService {
	private static final String EVENT_TYPE = "problem_generation.requested";
	private static final String SCHEMA_VERSION = "pg-request-1";
	private static final Pattern SKILL_NODE_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,119}");
	private static final Pattern CLIENT_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,200}");

	private final ProblemGenerationRequestRepository requests;
	private final ProblemGenerationOutboxRepository outbox;
	private final TeacherStudentRelationshipRepository relationships;
	private final ClassGroupRepository classes;
	private final AiStudentAliasService studentAliases;
	private final AiProblemAliasService problemAliases;
	private final ProblemGenerationIdGenerator ids;
	private final ProblemGenerationPayloadHasher hasher;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ProblemGenerationRequestService(ProblemGenerationRequestRepository requests,
		ProblemGenerationOutboxRepository outbox, TeacherStudentRelationshipRepository relationships,
		ClassGroupRepository classes, AiStudentAliasService studentAliases, AiProblemAliasService problemAliases,
		ProblemGenerationIdGenerator ids, ProblemGenerationPayloadHasher hasher,
		TeacherTenantDatabaseContext tenantContext, ObjectMapper objectMapper, Clock clock) {
		this.requests = requests; this.outbox = outbox; this.relationships = relationships; this.classes = classes;
		this.studentAliases = studentAliases; this.problemAliases = problemAliases; this.ids = ids; this.hasher = hasher;
		this.tenantContext = tenantContext; this.objectMapper = objectMapper; this.clock = clock;
	}

	@Transactional
	public CreationResult create(UUID authenticatedTeacherId, CreateProblemGenerationCommand rawCommand) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		NormalizedCommand command = normalize(rawCommand);
		tenantContext.setCurrentTeacher(teacherId);
		String tenantAlias = problemAliases.getOrCreateTenantAlias(teacherId);
		String targetRef = resolveTargetRef(teacherId, command);
		Instant now = Instant.now(clock);
		List<UUID> generatedIds = ids.nextIds(2);
		UUID requestId = generatedIds.get(0);
		UUID eventId = generatedIds.get(1);
		String aiIdempotencyKey = "pg_" + compact(requestId);

		LinkedHashMap<String, Object> aiPayload = buildAiPayload(command, targetRef);
		String snapshotHash = hasher.sha256(writeJson(aiPayload));
		aiPayload.put("snapshot_hash", snapshotHash);
		String requestPayload = writeJson(aiPayload);
		boolean inserted = requests.insert(new NewRequest(requestId, teacherId, tenantAlias, command.targetKind(),
			command.targetKind() == ProblemTargetKind.STUDENT ? command.targetId() : null,
			command.targetKind() == ProblemTargetKind.CLASS ? command.targetId() : null,
			targetRef, command.clientIdempotencyKey(), aiIdempotencyKey, snapshotHash, requestPayload, now));
		if (!inserted) {
			var existing = requests.findByClientKey(teacherId, command.clientIdempotencyKey())
				.orElseThrow(ProblemGenerationException::idempotencyConflict);
			if (!existing.snapshotHash().equals(snapshotHash)) throw ProblemGenerationException.idempotencyConflict();
			return new CreationResult(existing.id(), false);
		}
		String eventPayload = writeJson(buildEnvelope(eventId, requestId, tenantAlias, aiIdempotencyKey, aiPayload, now));
		outbox.insert(new NewOutboxEvent(eventId, teacherId, requestId, EVENT_TYPE, SCHEMA_VERSION, tenantAlias, eventPayload, now));
		return new CreationResult(requestId, true);
	}

	@Transactional(readOnly = true)
	public ProblemGenerationRequestView get(UUID authenticatedTeacherId, UUID requestId) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		Objects.requireNonNull(requestId, "requestId must not be null");
		tenantContext.setCurrentTeacher(teacherId);
		return requests.findByIdAndTeacherId(requestId, teacherId).orElseThrow(ProblemGenerationException::notFound);
	}

	private String resolveTargetRef(UUID teacherId, NormalizedCommand command) {
		if (command.targetKind() == ProblemTargetKind.STUDENT) {
			if (!relationships.existsByTeacherIdAndStudentIdAndStatus(teacherId, command.targetId(), RelationshipStatus.ACTIVE))
				throw ProblemGenerationException.inaccessibleTarget();
			return studentAliases.getOrCreate(teacherId, command.targetId());
		}
		var classGroup = classes.findByIdAndTeacherId(command.targetId(), teacherId)
			.filter(value -> value.status() == ClassGroupStatus.ACTIVE)
			.orElseThrow(ProblemGenerationException::inaccessibleTarget);
		return problemAliases.getOrCreateClassAlias(teacherId, classGroup.id());
	}

	private static LinkedHashMap<String, Object> buildAiPayload(NormalizedCommand command, String targetRef) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("target_kind", command.targetKind().name().toLowerCase(Locale.ROOT));
		payload.put("target_ref", targetRef);
		payload.put("target_source", "teacher_manual");
		payload.put("manual_targets", command.manualTargets());
		payload.put("taxonomy_version", command.taxonomyVersion());
		payload.put("area_tag", "language");
		payload.put("type_tags", command.typeTags());
		payload.put("item_format", "mcq");
		payload.put("count", command.count());
		payload.put("requested_difficulty", command.requestedDifficulty());
		payload.put("target", "auto");
		payload.put("passage", null);
		return payload;
	}

	private static LinkedHashMap<String, Object> buildEnvelope(UUID eventId, UUID requestId,
		String tenantAlias, String aiIdempotencyKey, Map<String, Object> aiPayload, Instant occurredAt) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("problem_request_id", requestId.toString());
		payload.put("idempotency_key", aiIdempotencyKey);
		payload.put("request", aiPayload);
		LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("event_id", eventId.toString()); envelope.put("event_type", EVENT_TYPE);
		envelope.put("occurred_at", occurredAt.toString()); envelope.put("tenant_id", tenantAlias);
		envelope.put("schema_version", SCHEMA_VERSION); envelope.put("correlation_id", requestId.toString());
		envelope.put("causation_id", null); envelope.put("payload", payload);
		return envelope;
	}

	private NormalizedCommand normalize(CreateProblemGenerationCommand command) {
		if (command == null || command.targetKind() == null || command.targetId() == null)
			throw ProblemGenerationException.invalidRequest("targetKind and targetId are required");
		if (command.count() < 1 || command.count() > 10)
			throw ProblemGenerationException.invalidRequest("count must be between 1 and 10 for v1");
		String taxonomyVersion = requireText(command.taxonomyVersion(), "taxonomyVersion", 80);
		List<String> manualTargets = normalizeManualTargets(command.manualTargets());
		List<String> typeTags = normalizeTypeTags(command);
		String difficulty = command.requestedDifficulty() == null ? null : command.requestedDifficulty().name().toLowerCase(Locale.ROOT);
		String clientKey = normalizeClientKey(command.clientIdempotencyKey());
		return new NormalizedCommand(command.targetKind(), command.targetId(), manualTargets, taxonomyVersion,
			typeTags, command.count(), difficulty, clientKey);
	}
	private static List<String> normalizeManualTargets(List<String> values) {
		if (values == null || values.isEmpty() || values.size() > 20)
			throw ProblemGenerationException.invalidRequest("manualTargets must contain between 1 and 20 skill node IDs");
		List<String> normalized = new ArrayList<>();
		for (String value : values) {
			String target = requireText(value, "manualTarget", 120);
			if (!SKILL_NODE_PATTERN.matcher(target).matches())
				throw ProblemGenerationException.invalidRequest("manualTargets contains an invalid skill node ID");
			normalized.add(target);
		}
		if (new LinkedHashSet<>(normalized).size() != normalized.size())
			throw ProblemGenerationException.invalidRequest("manualTargets must not contain duplicates");
		normalized.sort(Comparator.naturalOrder());
		return List.copyOf(normalized);
	}
	private static List<String> normalizeTypeTags(CreateProblemGenerationCommand command) {
		if (command.typeTags() == null || command.typeTags().isEmpty())
			throw ProblemGenerationException.invalidRequest("typeTags must not be empty");
		if (command.typeTags().size() > ProblemTypeTag.values().length || command.typeTags().stream().anyMatch(Objects::isNull))
			throw ProblemGenerationException.invalidRequest("typeTags contains an invalid value");
		if (new LinkedHashSet<>(command.typeTags()).size() != command.typeTags().size())
			throw ProblemGenerationException.invalidRequest("typeTags must not contain duplicates");
		return command.typeTags().stream().map(value -> value.name().toLowerCase(Locale.ROOT)).sorted().toList();
	}
	private static String normalizeClientKey(String value) {
		if (value == null || value.isBlank()) throw ProblemGenerationException.invalidRequest("Idempotency-Key is required");
		String normalized = value.trim();
		if (!CLIENT_KEY_PATTERN.matcher(normalized).matches())
			throw ProblemGenerationException.invalidRequest("Idempotency-Key must be 8..200 safe ASCII characters");
		return normalized;
	}
	private static String requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank()) throw ProblemGenerationException.invalidRequest(name + " must not be blank");
		String normalized = value.trim();
		if (normalized.length() > maxLength) throw ProblemGenerationException.invalidRequest(name + " is too long");
		return normalized;
	}
	private UUID requireTeacher(UUID teacherId) {
		if (teacherId == null) throw ProblemGenerationException.invalidPrincipal(); return teacherId;
	}
	private String writeJson(Object value) {
		try { return objectMapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new IllegalStateException("problem generation JSON serialization failed", exception); }
	}
	private static String compact(UUID value) { return value.toString().replace("-", ""); }
	public record CreationResult(UUID requestId, boolean created) { }
	private record NormalizedCommand(ProblemTargetKind targetKind, UUID targetId, List<String> manualTargets,
		String taxonomyVersion, List<String> typeTags, int count, String requestedDifficulty, String clientIdempotencyKey) { }
}
