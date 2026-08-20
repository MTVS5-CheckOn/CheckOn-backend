package com.checkon.counsel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository.NewJob;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.ai.CounselClientException;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

/**
 * Orchestrates the three counsel AI endpoints (§0 of the 2026-08-19 counsel
 * contract). No Kafka is involved — calls are synchronous REST to the AI
 * server, matching {@code problem.application.ProblemDiagnosisService}'s
 * request-thread pattern rather than the Kafka-outbox pattern used for
 * problem generation.
 */
@Service
public class CounselDraftService {

	private static final Logger log = LoggerFactory.getLogger(CounselDraftService.class);

	// The contract's own example key ("iq_884") is 6 characters, so no minimum
	// length is enforced here beyond "non-blank, safe ASCII" (§5, §③-5).
	private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

	private final CounselClient client;
	private final CounselDraftJobRepository jobs;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public CounselDraftService(
		CounselClient client,
		CounselDraftJobRepository jobs,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.client = client;
		this.jobs = jobs;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional
	public CreateCounselDraftResult createDraft(UUID teacherId, CreateCounselDraftCommand command) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		validateCreate(command);

		CounselDraftCreateRequest request = toRequest(command);
		CounselDraftCreateResponse response = call(() -> client.createDraft(
			request,
			new CounselClient.RequestHeaders(command.tenantAlias(), command.requestId(), command.idempotencyKey())
		));

		Instant now = Instant.now(clock);
		jobs.upsert(new NewJob(
			UUID.randomUUID(), resolvedTeacherId, command.tenantAlias(), command.inquiryRef(),
			command.studentRef(), command.parentRef(), command.classRef(), command.topic().wireValue(),
			command.idempotencyKey(), response.data().jobId(), response.data().status().wireValue(),
			response.meta() == null ? null : response.meta().executionId(), now, now
		));
		return new CreateCounselDraftResult(response.data().jobId(), response.data().status(), response.meta());
	}

	@Transactional
	public CounselDraftGetResponse getDraft(UUID teacherId, String tenantAlias, String jobId, String requestId) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		requireText(jobId, "jobId");
		requireText(tenantAlias, "tenantAlias");

		CounselDraftGetResponse response = call(() -> client.getDraft(jobId, tenantAlias, requestId));
		CounselJobPhase phase = response.data().status();
		jobs.updateKnownPhase(resolvedTeacherId, jobId, phase.wireValue(), Instant.now(clock));
		return response;
	}

	@Transactional
	public CounselDraftRefineResponse refine(UUID teacherId, RefineCounselDraftCommand command) {
		requireTeacher(teacherId);
		validateRefine(command);

		CounselDraftRefineRequest request = new CounselDraftRefineRequest(command.instruction(), command.turnNo());
		return call(() -> client.refineDraft(
			command.jobId(),
			request,
			new CounselClient.RequestHeaders(command.tenantAlias(), command.requestId(), command.idempotencyKey())
		));
	}

	/**
	 * Records the text the teacher actually sent through their own channel
	 * (contract appendix §7 — "발송본을 보관해 주세요"). No AI call is involved;
	 * this is purely local bookkeeping so a human can later compare it against
	 * the AI's last draft to see what teachers tend to edit.
	 */
	@Transactional
	public void markSent(UUID teacherId, String jobId, String sentText) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		requireText(jobId, "jobId");
		requireText(sentText, "sentText");
		if (!jobs.markSent(resolvedTeacherId, jobId, sentText, Instant.now(clock))) {
			throw CounselException.jobNotFound();
		}
	}

	/**
	 * Re-fetches every locally non-terminal job for this teacher so the stored
	 * phase does not go stale (§0-3: GET never advances a job, only a POST
	 * can unstick {@code queued} — this does not do that, it only keeps
	 * bookkeeping current). Called by {@link CounselDraftPollingJob} — kept on
	 * this proxied bean rather than a self-invoked method on the caller so
	 * {@code @Transactional} and the per-teacher RLS context actually apply.
	 */
	@Transactional
	public int refreshNonTerminalJobs(UUID teacherId) {
		tenantContext.setCurrentTeacher(teacherId);
		int refreshed = 0;
		for (var job : jobs.findNonTerminalByTeacher(teacherId)) {
			try {
				getDraft(teacherId, job.tenantAlias(), job.jobId(), null);
				refreshed++;
			}
			catch (RuntimeException exception) {
				log.warn("Counsel draft polling could not refresh job: teacherId={}, jobId={}, errorType={}",
					teacherId, job.jobId(), exception.getClass().getSimpleName());
			}
		}
		return refreshed;
	}

	private <T> T call(Supplier<T> aiCall) {
		try {
			return aiCall.get();
		}
		catch (CounselClientException exception) {
			throw switch (exception.reason()) {
				case IDEMPOTENCY_CONFLICT -> CounselException.idempotencyConflict();
				case NOT_FOUND -> CounselException.jobNotFound();
				case HTTP_ERROR, EMPTY_RESPONSE, NETWORK_ERROR -> CounselException.upstreamUnavailable(exception);
			};
		}
	}

	private static CounselDraftCreateRequest toRequest(CreateCounselDraftCommand command) {
		List<CounselDraftCreateRequest.DismissedSuggestion> dismissedSuggestions = command.dismissedSuggestions() == null
			? List.of()
			: command.dismissedSuggestions().stream()
				.map(value -> new CounselDraftCreateRequest.DismissedSuggestion(value.axis(), value.value()))
				.toList();
		List<CounselDraftCreateRequest.Fact> facts = command.facts() == null
			? List.of()
			: command.facts().stream()
				.map(value -> new CounselDraftCreateRequest.Fact(value.recordId(), value.summary()))
				.toList();
		return new CounselDraftCreateRequest(
			new CounselDraftCreateRequest.Inquiry(
				command.inquiryRef(), command.topic(), command.urgency(), command.receivedAt(), command.textMasked()
			),
			command.studentRef(), command.parentRef(), command.classRef(),
			command.labels() == null ? List.of() : List.copyOf(command.labels()),
			dismissedSuggestions,
			new CounselDraftCreateRequest.Context(command.snapshotHash(), command.periodLabel(), facts)
		);
	}

	private void validateCreate(CreateCounselDraftCommand command) {
		if (command == null) throw CounselException.invalidRequest("request body is required");
		requireText(command.tenantAlias(), "tenantAlias");
		requireText(command.requestId(), "requestId");
		requireIdempotencyKey(command.idempotencyKey());
		requireText(command.inquiryRef(), "inquiryRef");
		if (command.topic() == null) throw CounselException.invalidRequest("topic is required");
		if (command.urgency() == null) throw CounselException.invalidRequest("urgency is required");
		if (command.receivedAt() == null) throw CounselException.invalidRequest("receivedAt is required");
		requireText(command.textMasked(), "textMasked");
		requireText(command.studentRef(), "studentRef");
		requireText(command.parentRef(), "parentRef");
		requireText(command.classRef(), "classRef");
		requireText(command.snapshotHash(), "snapshotHash");
		requireText(command.periodLabel(), "periodLabel");
		if (command.facts() == null) throw CounselException.invalidRequest("facts is required (an empty list is allowed)");
	}

	private void validateRefine(RefineCounselDraftCommand command) {
		if (command == null) throw CounselException.invalidRequest("request body is required");
		requireText(command.tenantAlias(), "tenantAlias");
		requireIdempotencyKey(command.idempotencyKey());
		requireText(command.jobId(), "jobId");
		requireText(command.instruction(), "instruction");
	}

	private static void requireIdempotencyKey(String value) {
		if (value == null || !IDEMPOTENCY_KEY_PATTERN.matcher(value).matches())
			throw CounselException.invalidRequest("Idempotency-Key must be 8..200 safe ASCII characters");
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) throw CounselException.invalidRequest(name + " must not be blank");
	}

	private static UUID requireTeacher(UUID teacherId) {
		if (teacherId == null) throw CounselException.invalidPrincipal();
		return teacherId;
	}

	public record CreateCounselDraftResult(
		String jobId,
		CounselJobPhase status,
		CounselMeta meta
	) {
	}
}
