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
import com.checkon.problem.infrastructure.persistence.ProblemGenerationExecutionRepository;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationExecutionRepository.NewExecution;
import com.checkon.problem.infrastructure.persistence.ProblemStudioWorkflowRepository;
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
	private static final String MVP_STUDIO_AREA = "language";
	private static final List<ProblemTypeTag> MVP_STUDIO_TYPES = List.of(
		ProblemTypeTag.CONCEPT,
		ProblemTypeTag.INFER
	);

	private final ProblemGenerationRequestRepository requests;
	private final ProblemGenerationExecutionRepository executions;
	private final ProblemStudioWorkflowRepository studioWorkflow;
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

	public ProblemGenerationRequestService(ProblemGenerationRequestRepository requests, ProblemGenerationExecutionRepository executions,
		ProblemStudioWorkflowRepository studioWorkflow, ProblemGenerationOutboxRepository outbox,
		TeacherStudentRelationshipRepository relationships,
		ClassGroupRepository classes, AiStudentAliasService studentAliases, AiProblemAliasService problemAliases,
		ProblemGenerationIdGenerator ids, ProblemGenerationPayloadHasher hasher,
		TeacherTenantDatabaseContext tenantContext, ObjectMapper objectMapper, Clock clock) {
		this.requests = requests; this.executions = executions; this.studioWorkflow = studioWorkflow; this.outbox = outbox;
		this.relationships = relationships; this.classes = classes;
		this.studentAliases = studentAliases; this.problemAliases = problemAliases; this.ids = ids; this.hasher = hasher;
		this.tenantContext = tenantContext; this.objectMapper = objectMapper; this.clock = clock;
	}

	@Transactional
	public CreationResult create(UUID authenticatedTeacherId, CreateProblemGenerationCommand rawCommand) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		NormalizedCommand command = normalize(rawCommand);
		return createNormalized(teacherId, command, List.of());
	}

	@Transactional
	public CreationResult createStudio(UUID authenticatedTeacherId, CreateProblemStudioCommand rawCommand) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		StudioCommand studio = normalizeStudio(rawCommand);
		return createNormalized(teacherId, studio.command(), studio.targets());
	}

	private CreationResult createNormalized(
		UUID teacherId,
		NormalizedCommand command,
		List<CreateProblemStudioCommand.Target> studioTargets
	) {
		tenantContext.setCurrentTeacher(teacherId);
		String tenantAlias = problemAliases.getOrCreateTenantAlias(teacherId);
		String targetRef = resolveTargetRef(teacherId, command);
		Instant now = Instant.now(clock);
		List<UUID> generatedIds = ids.nextIds(2);
		UUID requestId = generatedIds.get(0);
		UUID eventId = generatedIds.get(1);
		String aiIdempotencyKey = "pg_" + compact(requestId);

		LinkedHashMap<String, Object> aiPayload = studioTargets.isEmpty()
			? buildAiPayload(command, targetRef)
			: buildStudioAiPayload(command, targetRef, studioTargets);
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
		if (!studioTargets.isEmpty()) {
			List<UUID> targetIds = studioWorkflow.insertTargets(teacherId, requestId, studioTargets);
			List<UUID> childIds = ids.nextIds(studioTargets.size() * 2);
			for (int index = 0; index < studioTargets.size(); index++) {
				UUID executionId = childIds.get(index * 2);
				UUID childEventId = childIds.get(index * 2 + 1);
				String childKey = "pgc_" + compact(executionId);
				LinkedHashMap<String,Object> childRequest = buildStudioChildPayload(command, targetRef, studioTargets.get(index));
				String childHash = hasher.sha256(writeJson(childRequest));
				childRequest.put("snapshot_hash", childHash);
				String childSnapshot = writeJson(childRequest);
				executions.insert(new NewExecution(executionId, teacherId, requestId, targetIds.get(index), index,
					childKey, childHash, childSnapshot, now));
				String childEvent = writeJson(buildChildEnvelope(childEventId, requestId, executionId, index,
					tenantAlias, childKey, childRequest, now));
				outbox.insert(new NewOutboxEvent(childEventId, teacherId, requestId, executionId,
					EVENT_TYPE, "pg-child-request-1", tenantAlias, childEvent, now));
			}
		} else {
			String eventPayload = writeJson(buildEnvelope(eventId, requestId, tenantAlias, aiIdempotencyKey, aiPayload, now));
			outbox.insert(new NewOutboxEvent(eventId, teacherId, requestId, null, EVENT_TYPE, SCHEMA_VERSION, tenantAlias, eventPayload, now));
		}
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

	private static LinkedHashMap<String, Object> buildStudioAiPayload(
		NormalizedCommand command,
		String targetRef,
		List<CreateProblemStudioCommand.Target> targets
	) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("target_kind", "student");
		payload.put("target_ref", targetRef);
		payload.put("target_source", "teacher_weakness_selection");
		payload.put("taxonomy_version", command.taxonomyVersion());
		List<Map<String, Object>> generationTargets = targets.stream().map(target -> {
			LinkedHashMap<String, Object> value = new LinkedHashMap<>();
			value.put("area_tag", target.areaTag());
			value.put("type_tag", target.typeTag().name().toLowerCase(Locale.ROOT));
			value.put("count", target.count());
			return Map.copyOf(value);
		}).toList();
		payload.put("generation_targets", generationTargets);
		List<String> areas = targets.stream().map(CreateProblemStudioCommand.Target::areaTag).distinct().toList();
		payload.put("area_tag", areas.size() == 1 ? areas.getFirst() : "mixed");
		payload.put("type_tags", command.typeTags());
		payload.put("item_format", "mcq");
		payload.put("count", command.count());
		payload.put("requested_difficulty", command.requestedDifficulty());
		payload.put("target", "auto");
		payload.put("passage", null);
		payload.put("analysis_window_weeks", 8);
		return payload;
	}

	private static LinkedHashMap<String,Object> buildStudioChildPayload(NormalizedCommand command, String targetRef,
		CreateProblemStudioCommand.Target target) {
		LinkedHashMap<String,Object> payload = new LinkedHashMap<>();
		payload.put("target_kind", "student"); payload.put("target_ref", targetRef);
		payload.put("target_source", "teacher_manual"); payload.put("taxonomy_version", command.taxonomyVersion());
		payload.put("area_tag", target.areaTag()); payload.put("type_tags", List.of(target.typeTag().name().toLowerCase(Locale.ROOT)));
		payload.put("item_format", "mcq"); payload.put("count", target.count());
		payload.put("requested_difficulty", command.requestedDifficulty()); payload.put("target", "auto"); payload.put("passage", null);
		return payload;
	}

	private static LinkedHashMap<String,Object> buildChildEnvelope(UUID eventId, UUID requestId, UUID executionId,
		int targetIndex, String tenantAlias, String aiKey, Map<String,Object> request, Instant occurredAt) {
		LinkedHashMap<String,Object> payload = new LinkedHashMap<>();
		payload.put("problem_request_id",requestId.toString()); payload.put("problem_execution_id",executionId.toString());
		payload.put("target_index",targetIndex); payload.put("idempotency_key",aiKey); payload.put("request",request);
		LinkedHashMap<String,Object> envelope = new LinkedHashMap<>();
		envelope.put("event_id",eventId.toString()); envelope.put("event_type",EVENT_TYPE); envelope.put("occurred_at",occurredAt.toString());
		envelope.put("tenant_id",tenantAlias); envelope.put("schema_version","pg-child-request-1");
		envelope.put("correlation_id",requestId.toString()); envelope.put("causation_id",null); envelope.put("payload",payload);
		return envelope;
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

	private StudioCommand normalizeStudio(CreateProblemStudioCommand command) {
		if (command == null || command.studentId() == null)
			throw ProblemGenerationException.invalidRequest("studentId is required");
		if (command.difficulty() == null)
			throw ProblemGenerationException.invalidRequest("difficulty is required");
		if (command.targets() == null || command.targets().isEmpty() || command.targets().size() > 20)
			throw ProblemGenerationException.invalidRequest("targets must contain between 1 and 20 entries");
		List<CreateProblemStudioCommand.Target> targets = new ArrayList<>();
		for (CreateProblemStudioCommand.Target target : command.targets()) {
			if (target == null || target.typeTag() == null || target.count() < 1 || target.count() > 20)
				throw ProblemGenerationException.invalidRequest("each target requires a type and count between 1 and 20");
			String areaTag = requireText(target.areaTag(), "areaTag", 80).toLowerCase(Locale.ROOT);
			if (!MVP_STUDIO_AREA.equals(areaTag) || !MVP_STUDIO_TYPES.contains(target.typeTag()))
				throw ProblemGenerationException.invalidRequest(
					"v1 supports only language area with CONCEPT or INFER type"
				);
			targets.add(new CreateProblemStudioCommand.Target(
				areaTag, target.typeTag(), target.count()
			));
		}
		if (new LinkedHashSet<>(targets.stream().map(value -> value.areaTag() + "\u0000" + value.typeTag()).toList()).size()
			!= targets.size())
			throw ProblemGenerationException.invalidRequest("targets must not contain duplicate area and type pairs");
		int totalCount = targets.stream().mapToInt(CreateProblemStudioCommand.Target::count).sum();
		if (totalCount < 1 || totalCount > 20)
			throw ProblemGenerationException.invalidRequest("the total problem count must be between 1 and 20");
		List<String> typeTags = targets.stream().map(value -> value.typeTag().name().toLowerCase(Locale.ROOT))
			.distinct().sorted().toList();
		NormalizedCommand normalized = new NormalizedCommand(
			ProblemTargetKind.STUDENT, command.studentId(), List.of(), "frontend-studio-1",
			typeTags, totalCount, command.difficulty().name().toLowerCase(Locale.ROOT),
			normalizeClientKey(command.clientIdempotencyKey())
		);
		return new StudioCommand(normalized, List.copyOf(targets));
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
	private record StudioCommand(NormalizedCommand command, List<CreateProblemStudioCommand.Target> targets) { }
}
