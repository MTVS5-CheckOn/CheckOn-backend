package com.checkon.problem.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationConsumedEventRepository;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRequestRepository;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationExecutionRepository;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRequestRepository.ResultUpdate;
import com.checkon.problem.integration.kafka.ParsedProblemGenerationResultEvent;
import com.checkon.problem.integration.kafka.ProblemGenerationEventContractException;

@Service
public class ProblemGenerationResultProcessor {
	private final ProblemGenerationRequestRepository requests;
	private final ProblemGenerationExecutionRepository executions;
	private final ProblemGenerationConsumedEventRepository consumedEvents;
	private final ProblemGenerationItemProjector itemProjector;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;
	public ProblemGenerationResultProcessor(ProblemGenerationRequestRepository requests, ProblemGenerationExecutionRepository executions,
		ProblemGenerationConsumedEventRepository consumedEvents, ProblemGenerationItemProjector itemProjector,
		TeacherTenantDatabaseContext tenantContext, Clock clock) {
		this.requests = requests; this.executions = executions; this.consumedEvents = consumedEvents; this.itemProjector = itemProjector;
		this.tenantContext = tenantContext; this.clock = clock;
	}

	@Transactional
	public void process(UUID teacherId, ParsedProblemGenerationResultEvent event) {
		tenantContext.setCurrentTeacher(teacherId);
		var request = requests.findByIdAndTeacherIdForUpdate(event.requestId(), teacherId)
			.orElseThrow(() -> contract("problem request was not found for tenant"));
		if (!request.tenantAlias().equals(event.tenantAlias())) throw contract("event tenant alias does not match problem request");
		var consumedHash = consumedEvents.findPayloadHash(event.eventId());
		if (consumedHash.isPresent()) {
			if (!consumedHash.get().equals(event.payloadHash())) throw contract("event_id was reused with another payload");
			return;
		}
		if (!consumedEvents.insert(event.eventId(), teacherId, event.requestId(), event.eventType(), event.payloadHash(), Instant.now(clock)))
			throw contract("event_id was already consumed by another request");
		if (event.problemExecutionId() != null) {
			processChild(teacherId, request, event);
			return;
		}

		verifyStableId("job_id", request.jobId(), event.jobId());
		verifyStableId("execution_id", request.executionId(), event.aiExecutionId());
		verifyStableId("set_id", request.setId(), event.setId());
		if (request.status().terminal()) {
			if (request.status() != event.status()) throw contract("terminal result cannot be replaced by another phase");
			return;
		}
		Instant now = Instant.now(clock);
		ProblemGenerationStatus next = event.status();
		String error = next == ProblemGenerationStatus.FAILED
			? firstNonBlank(event.errorCode(), "AI_EXECUTION_FAILED") : null;
		requests.applyResult(new ResultUpdate(request.id(), teacherId, next, event.jobId(), event.aiExecutionId(),
			event.setId(), event.resultStatus(), error, event.resultPayload(), event.versionsPayload(),
			next.terminal() ? event.occurredAt() : null, now));
		if (next == ProblemGenerationStatus.SUCCEEDED) {
			itemProjector.project(teacherId, request.id(), event.resultPayload());
		}
	}

	private void processChild(UUID teacherId, ProblemGenerationRequestRepository.LockedRequest request,
		ParsedProblemGenerationResultEvent event) {
		var child = executions.findForUpdate(event.problemExecutionId(), teacherId, request.id())
			.orElseThrow(() -> contract("problem execution was not found for request"));
		if (event.targetIndex() == null || event.targetIndex() != child.targetIndex()) throw contract("target_index does not match child execution");
		verifyStableId("adapter_execution_id", child.adapterExecutionId() == null ? null : child.adapterExecutionId().toString(),
			event.adapterExecutionId() == null ? null : event.adapterExecutionId().toString());
		verifyStableId("job_id",child.jobId(),event.jobId()); verifyStableId("execution_id",child.aiExecutionId(),event.aiExecutionId());
		verifyStableId("set_id",child.setId(),event.setId());
		if (child.status().terminal()) {
			if (child.status() != event.executionStatus()) throw contract("terminal child result cannot be replaced");
			return;
		}
		Instant now = Instant.now(clock);
		String error = event.executionStatus().failureForParentAggregation() ? firstNonBlank(event.errorCode(),"AI_EXECUTION_FAILED") : null;
		executions.applyResult(new ProblemGenerationExecutionRepository.ResultUpdate(child.id(),teacherId,event.executionStatus(),
			event.adapterExecutionId(),event.aiExecutionId(),event.jobId(),event.setId(),event.resultStatus(),error,
			event.resultPayload(),event.versionsPayload(),event.executionStatus().terminal()?event.occurredAt():null,now));
		if (event.executionStatus() == com.checkon.problem.domain.ProblemGenerationExecutionStatus.SUCCEEDED)
			itemProjector.project(teacherId,request.id(),event.resultPayload());
		aggregateParent(teacherId,request.id(),now);
	}

	private void aggregateParent(UUID teacherId, UUID requestId, Instant now) {
		var statuses = executions.statuses(teacherId,requestId);
		if (statuses.isEmpty()) throw contract("problem request has no child executions");
		ProblemGenerationStatus parent;
		if (statuses.stream().anyMatch(status -> !status.terminal())) parent = ProblemGenerationStatus.RUNNING;
		else {
			boolean success = itemProjector.hasProjectedItems(teacherId,requestId);
			boolean failed = statuses.stream().anyMatch(com.checkon.problem.domain.ProblemGenerationExecutionStatus::failureForParentAggregation);
			if (success && failed) parent = ProblemGenerationStatus.PARTIAL_SUCCESS;
			else if (success || statuses.stream().allMatch(status -> status == com.checkon.problem.domain.ProblemGenerationExecutionStatus.REJECTED_INSUFFICIENT)) parent = ProblemGenerationStatus.SUCCEEDED;
			else parent = ProblemGenerationStatus.FAILED;
		}
		requests.updateAggregatedStatus(requestId,teacherId,parent,parent==ProblemGenerationStatus.FAILED?"ALL_CHILD_EXECUTIONS_FAILED":null,now);
	}
	private static void verifyStableId(String name, String current, String incoming) {
		if (current != null && incoming != null && !Objects.equals(current, incoming)) throw contract(name + " changed during one problem request");
	}
	private static String firstNonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
	private static ProblemGenerationEventContractException contract(String message) { return new ProblemGenerationEventContractException(message); }
}
