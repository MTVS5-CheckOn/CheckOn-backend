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
import com.checkon.counsel.infrastructure.kafka.CounselDraftOutboxRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository.NewJob;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.ai.CounselClientException;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.counsel.integration.kafka.CounselDraftKafkaEvent;
import com.checkon.counsel.integration.kafka.CounselDraftKafkaProperties;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.application.ProblemGenerationPayloadHasher;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates the three counsel AI endpoints. As of 2026-08-20, only create
 * is Kafka-async (published to {@code checkon.counsel-draft.requested.v1},
 * resolved by a completion/failure event on {@code .completed.v1}/{@code
 * .failed.v1}) — the AI team's 8/19 "no Kafka for counsel" guidance was
 * withdrawn; {@code 04_api_contract.md} had already confirmed Kafka
 * completion notification. GET and refine stay direct synchronous REST:
 * GET is the AI's own contract ("본문은 REST GET으로 회수합니다"), and refine
 * is a turn-based interactive contract that was never proposed for Kafka.
 */
@Service
public class CounselDraftService {

	private static final Logger log = LoggerFactory.getLogger(CounselDraftService.class);

	// The contract's own example key ("iq_884") is 6 characters, so no minimum
	// length is enforced here beyond "non-blank, safe ASCII" (§5, §③-5).
	private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

	private final CounselClient client;
	private final CounselDraftJobRepository jobs;
	private final CounselDraftOutboxRepository outbox;
	private final CounselDraftKafkaProperties kafkaProperties;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ProblemGenerationPayloadHasher hasher;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public CounselDraftService(
		CounselClient client,
		CounselDraftJobRepository jobs,
		CounselDraftOutboxRepository outbox,
		CounselDraftKafkaProperties kafkaProperties,
		TeacherTenantDatabaseContext tenantContext,
		ProblemGenerationPayloadHasher hasher,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.client = client;
		this.jobs = jobs;
		this.outbox = outbox;
		this.kafkaProperties = kafkaProperties;
		this.tenantContext = tenantContext;
		this.hasher = hasher;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * Mints the job id, persists it as {@code queued}, and publishes the AI
	 * request to the outbox — the actual AI call happens on the adapter's
	 * side once it consumes {@code checkon.counsel-draft.requested.v1}. A
	 * replay of the same (teacher, Idempotency-Key) returns the existing job
	 * without publishing again; a different body under the same key is a 409.
	 */
	@Transactional
	public CreateCounselDraftResult createDraft(UUID teacherId, CreateCounselDraftCommand command) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		validateCreate(command);

		CounselDraftCreateRequest request = toRequest(command);
		String requestHash = hasher.sha256(writeJson(request));
		UUID jobUuid = UUID.randomUUID();
		String jobId = jobUuid.toString();
		Instant now = Instant.now(clock);

		boolean inserted = jobs.insertIfAbsent(new NewJob(
			jobUuid, resolvedTeacherId, command.tenantAlias(), command.inquiryRef(),
			command.studentRef(), command.parentRef(), command.classRef(), command.topic().wireValue(),
			command.idempotencyKey(), jobId, null, CounselJobPhase.QUEUED.wireValue(), null,
			requestHash, now, now
		));
		if (!inserted) {
			var existing = jobs.findByTeacherAndIdempotencyKey(resolvedTeacherId, command.idempotencyKey())
				.orElseThrow(CounselException::idempotencyConflict);
			if (!existing.requestHash().equals(requestHash)) throw CounselException.idempotencyConflict();
			return new CreateCounselDraftResult(existing.jobId(), CounselJobPhase.fromWireValue(existing.jobPhase()), null);
		}

		UUID eventId = UUID.randomUUID();
		CounselDraftKafkaEvent<CounselDraftCreateRequest> event = new CounselDraftKafkaEvent<>(
			eventId, CounselDraftKafkaEvent.REQUESTED, CounselDraftKafkaEvent.SCHEMA_VERSION,
			jobUuid, eventId, command.tenantAlias(), jobUuid, jobUuid,
			command.requestId(), command.idempotencyKey(), command.snapshotHash(), now, request
		);
		outbox.insert(new CounselDraftOutboxRepository.NewEvent(
			eventId, resolvedTeacherId, jobUuid, kafkaProperties.requestedTopic(),
			command.tenantAlias(), writeJson(event), now
		));
		return new CreateCounselDraftResult(jobId, CounselJobPhase.QUEUED, null);
	}

	/**
	 * If {@code ai_job_id} is not known yet (completion event has not arrived),
	 * this returns the locally known phase without calling the AI at all —
	 * there is nothing to fetch a body from yet. Once known, this calls the
	 * AI's real GET endpoint, since the completion event payload intentionally
	 * carries no draft body ("payload에 초안 본문·문의 원문을 싣지 않습니다").
	 */
	@Transactional
	public CounselDraftGetResponse getDraft(UUID teacherId, String tenantAlias, String jobId, String requestId) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		requireText(jobId, "jobId");

		var job = jobs.findByTeacherAndJobId(resolvedTeacherId, jobId).orElseThrow(CounselException::jobNotFound);
		if (job.aiJobId() == null) {
			return new CounselDraftGetResponse(
				new CounselDraftGetResponse.Data(job.jobId(), CounselJobPhase.fromWireValue(job.jobPhase()), null),
				null, null
			);
		}

		requireText(tenantAlias, "tenantAlias");
		CounselDraftGetResponse response = call(() -> client.getDraft(job.aiJobId(), tenantAlias, requestId));
		CounselJobPhase phase = response.data().status();
		jobs.updateKnownPhase(resolvedTeacherId, jobId, phase.wireValue(), Instant.now(clock));
		return response;
	}

	/** {@code command.jobId()} is the backend-minted id (§ above) — this translates it to the AI's real job id before calling refine. */
	@Transactional
	public CounselDraftRefineResponse refine(UUID teacherId, RefineCounselDraftCommand command) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		validateRefine(command);

		var job = jobs.findByTeacherAndJobId(resolvedTeacherId, command.jobId()).orElseThrow(CounselException::jobNotFound);
		if (job.aiJobId() == null) throw CounselException.draftNotReady();

		CounselDraftRefineRequest request = new CounselDraftRefineRequest(command.instruction(), command.turnNo());
		return call(() -> client.refineDraft(
			job.aiJobId(),
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
	 * phase does not go stale. Jobs still waiting on the Kafka completion event
	 * ({@code ai_job_id} unknown) are skipped — there is nothing to poll for
	 * them yet. Called by {@link CounselDraftPollingJob} — kept on this
	 * proxied bean rather than a self-invoked method on the caller so
	 * {@code @Transactional} and the per-teacher RLS context actually apply.
	 */
	@Transactional
	public int refreshNonTerminalJobs(UUID teacherId) {
		tenantContext.setCurrentTeacher(teacherId);
		int refreshed = 0;
		for (var job : jobs.findNonTerminalByTeacher(teacherId)) {
			if (job.aiJobId() == null) continue;
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

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("counsel Kafka payload could not be serialized", exception);
		}
	}

	public record CreateCounselDraftResult(
		String jobId,
		CounselJobPhase status,
		CounselMeta meta
	) {
	}
}
