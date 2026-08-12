package com.checkon.problem.infrastructure.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxRepository.OutboxMessage;
import com.checkon.problem.infrastructure.persistence.ProblemGenerationRequestRepository;
import com.checkon.problem.integration.kafka.ProblemGenerationKafkaProperties;

@Service
public class ProblemGenerationOutboxCoordinator {
	private final ProblemGenerationOutboxRepository outbox;
	private final ProblemGenerationRequestRepository requests;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ProblemGenerationKafkaProperties properties;
	private final Clock clock;

	public ProblemGenerationOutboxCoordinator(ProblemGenerationOutboxRepository outbox,
		ProblemGenerationRequestRepository requests, TeacherTenantDatabaseContext tenantContext,
		ProblemGenerationKafkaProperties properties, Clock clock) {
		this.outbox = outbox; this.requests = requests; this.tenantContext = tenantContext;
		this.properties = properties; this.clock = clock;
	}
	public List<UUID> teacherIds() { return outbox.findAllTeacherIds(); }

	@Transactional
	public List<OutboxMessage> claim(UUID teacherId) {
		tenantContext.setCurrentTeacher(teacherId);
		Instant now = Instant.now(clock);
		return outbox.claimBatch(teacherId, now, now.minus(properties.staleClaimAfter()), properties.outboxBatchSize());
	}
	@Transactional
	public void published(OutboxMessage message) {
		tenantContext.setCurrentTeacher(message.teacherId());
		Instant now = Instant.now(clock);
		outbox.markPublished(message.id(), message.teacherId(), now);
		requests.markDispatched(message.requestId(), message.teacherId(), now);
	}
	@Transactional
	public void failed(OutboxMessage message, RuntimeException failure) {
		tenantContext.setCurrentTeacher(message.teacherId());
		String safeError = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
		if (message.attemptCount() >= properties.outboxMaxAttempts()) {
			outbox.markDead(message.id(), message.teacherId(), safeError);
			requests.markDeliveryFailed(message.requestId(), message.teacherId(), Instant.now(clock));
			return;
		}
		Duration delay = Duration.ofSeconds(Math.min(60L, 1L << Math.min(message.attemptCount(), 6)));
		outbox.markPending(message.id(), message.teacherId(), Instant.now(clock).plus(delay), safeError);
	}
}
