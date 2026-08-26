package com.checkon.problem.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository.NewOutboxEvent;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRevisionRepository;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRevisionRepository.NewRevision;
import com.checkon.problem.infrastructure.persistence.ProblemStudioWorkflowRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProblemGenerationRevisionService {
	private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,200}");
	private static final String EVENT_TYPE = "problem_generation.revision.requested";
	private static final String SCHEMA_VERSION = "pg-revision-request-1";
	private final ProblemStudioWorkflowRepository workflow;
	private final ProblemGenerationRevisionRepository revisions;
	private final ProblemGenerationOutboxRepository outbox;
	private final AiProblemAliasService aliases;
	private final ProblemGenerationIdGenerator ids;
	private final ProblemGenerationPayloadHasher hasher;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ProblemGenerationRevisionService(ProblemStudioWorkflowRepository workflow,
		ProblemGenerationRevisionRepository revisions, ProblemGenerationOutboxRepository outbox,
		AiProblemAliasService aliases, ProblemGenerationIdGenerator ids, ProblemGenerationPayloadHasher hasher,
		TeacherTenantDatabaseContext tenantContext, ObjectMapper objectMapper, Clock clock) {
		this.workflow=workflow; this.revisions=revisions; this.outbox=outbox; this.aliases=aliases;
		this.ids=ids; this.hasher=hasher; this.tenantContext=tenantContext; this.objectMapper=objectMapper;
		this.clock=clock;
	}

	@Transactional
	public RevisionView refine(UUID authenticatedTeacherId, UUID requestId, UUID executionId, int slotIndex,
		int baseRevisionNo, String revisionKind, String instruction, String rawClientKey) {
		if (authenticatedTeacherId == null) throw ProblemGenerationException.invalidPrincipal();
		if (requestId == null || executionId == null || slotIndex < 0 || baseRevisionNo < 0)
			throw ProblemGenerationException.invalidRequest("request, execution, slot, and base revision are required");
		if (!"ai_refine".equals(revisionKind))
			throw ProblemGenerationException.invalidRequest("revisionKind must be ai_refine");
		String normalizedInstruction = requiredText(instruction,"instruction",2000);
		String clientKey = requiredText(rawClientKey,"Idempotency-Key",200);
		if (!CLIENT_KEY.matcher(clientKey).matches())
			throw ProblemGenerationException.invalidRequest("Idempotency-Key must be safe ASCII");
		tenantContext.setCurrentTeacher(authenticatedTeacherId);
		if (workflow.hasSavedSet(authenticatedTeacherId, requestId))
			throw ProblemGenerationException.invalidState("a saved problem set cannot be revised");
		var target = workflow.findRevisionTarget(authenticatedTeacherId,requestId,executionId,slotIndex)
			.orElseThrow(ProblemGenerationException::notFound);
		if (target.itemId() == null || target.aiSetId() == null || !containsRefine(target.availableActionsPayload()))
			throw ProblemGenerationException.invalidState("the selected slot does not allow ai_refine");
		if (baseRevisionNo != target.currentRevisionNo())
			throw ProblemGenerationException.revisionConflict("stale_base_revision",target.currentRevisionNo());

		LinkedHashMap<String,Object> logicalRequest=new LinkedHashMap<>();
		logicalRequest.put("request_id",requestId.toString()); logicalRequest.put("problem_execution_id",executionId.toString());
		logicalRequest.put("set_id",target.aiSetId()); logicalRequest.put("slot_index",slotIndex);
		logicalRequest.put("base_revision_no",baseRevisionNo); logicalRequest.put("revision_kind","ai_refine");
		logicalRequest.put("instruction",normalizedInstruction);
		String requestHash=hasher.sha256(write(logicalRequest));
		var replay=revisions.findByClientKey(authenticatedTeacherId,clientKey);
		if(replay.isPresent()) {
			if(!replay.get().requestHash().equals(requestHash)) throw ProblemGenerationException.idempotencyConflict();
			return new RevisionView(replay.get().id(),replay.get().status(),true);
		}
		if(revisions.hasActive(authenticatedTeacherId,executionId,slotIndex))
			throw ProblemGenerationException.revisionConflict("revision_in_progress",target.currentRevisionNo());

		var generated=ids.nextIds(2); UUID revisionId=generated.get(0); UUID eventId=generated.get(1);
		Instant now=Instant.now(clock);
		revisions.insert(new NewRevision(revisionId,authenticatedTeacherId,requestId,executionId,slotIndex,
			baseRevisionNo,normalizedInstruction,clientKey,requestHash,now));
		String tenantAlias=aliases.getOrCreateTenantAlias(authenticatedTeacherId);
		LinkedHashMap<String,Object> payload=new LinkedHashMap<>(logicalRequest);
		payload.put("revision_request_id",revisionId.toString());
		payload.put("idempotency_key","problem-revision:"+revisionId);
		Map<String,Object> envelope=Map.of(
			"event_id",eventId.toString(),"event_type",EVENT_TYPE,"occurred_at",now.toString(),
			"tenant_id",tenantAlias,"schema_version",SCHEMA_VERSION,"correlation_id",requestId.toString(),
			"payload",payload);
		outbox.insert(new NewOutboxEvent(eventId,authenticatedTeacherId,requestId,executionId,revisionId,
			EVENT_TYPE,SCHEMA_VERSION,tenantAlias,write(envelope),now));
		return new RevisionView(revisionId,"PENDING",false);
	}

	private boolean containsRefine(String value) {
		try { var root=objectMapper.readTree(value); if(root==null||!root.isArray()) return false;
			for(var node:root) if(node.isTextual()&&"refine".equals(node.asText())) return true;
			return false; }
		catch (JacksonException exception) { throw ProblemGenerationException.invalidState("stored available_actions is invalid"); }
	}
	private String write(Object value) { try { return objectMapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new IllegalStateException("revision JSON serialization failed",exception); } }
	private static String requiredText(String value,String name,int max) {
		if(value==null||value.isBlank()) throw ProblemGenerationException.invalidRequest(name+" must not be blank");
		String normalized=value.trim(); if(normalized.length()>max) throw ProblemGenerationException.invalidRequest(name+" is too long");
		return normalized;
	}

	public record RevisionView(UUID revisionRequestId,String status,boolean replayed) { }
}
