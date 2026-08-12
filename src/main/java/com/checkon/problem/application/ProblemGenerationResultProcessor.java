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
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRequestRepository.ResultUpdate;
import com.checkon.problem.integration.kafka.ParsedProblemGenerationResultEvent;
import com.checkon.problem.integration.kafka.ProblemGenerationEventContractException;

@Service
public class ProblemGenerationResultProcessor {
	private final ProblemGenerationRequestRepository requests;
	private final ProblemGenerationConsumedEventRepository consumedEvents;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;
	public ProblemGenerationResultProcessor(ProblemGenerationRequestRepository requests,
		ProblemGenerationConsumedEventRepository consumedEvents, TeacherTenantDatabaseContext tenantContext, Clock clock) {
		this.requests = requests; this.consumedEvents = consumedEvents; this.tenantContext = tenantContext; this.clock = clock;
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

		verifyStableId("job_id", request.jobId(), event.jobId());
		verifyStableId("execution_id", request.executionId(), event.executionId());
		verifyStableId("set_id", request.setId(), event.setId());
		if (request.status().terminal()) {
			if (request.status() != event.status()) throw contract("terminal result cannot be replaced by another phase");
			return;
		}
		Instant now = Instant.now(clock);
		ProblemGenerationStatus next = event.status();
		String error = next == ProblemGenerationStatus.FAILED
			? firstNonBlank(event.errorCode(), "AI_EXECUTION_FAILED") : null;
		requests.applyResult(new ResultUpdate(request.id(), teacherId, next, event.jobId(), event.executionId(),
			event.setId(), event.resultStatus(), error, event.resultPayload(), event.versionsPayload(),
			next.terminal() ? event.occurredAt() : null, now));
	}
	private static void verifyStableId(String name, String current, String incoming) {
		if (current != null && incoming != null && !Objects.equals(current, incoming)) throw contract(name + " changed during one problem request");
	}
	private static String firstNonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
	private static ProblemGenerationEventContractException contract(String message) { return new ProblemGenerationEventContractException(message); }
}
