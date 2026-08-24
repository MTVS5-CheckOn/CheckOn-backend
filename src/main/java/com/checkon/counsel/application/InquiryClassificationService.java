package com.checkon.counsel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.domain.ConfirmationAction;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.domain.InquirySentiment;
import com.checkon.counsel.infrastructure.persistence.InquiryClassificationRepository;
import com.checkon.counsel.infrastructure.persistence.InquiryClassificationRepository.NewClassification;
import com.checkon.counsel.integration.ai.ClassifyClient;
import com.checkon.counsel.integration.ai.ClassifyClientException;
import com.checkon.counsel.integration.ai.dto.ClassifyRequest;
import com.checkon.counsel.integration.ai.dto.ClassifyResponse;
import com.checkon.counsel.integration.ai.dto.ConfirmationRequest;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.application.AiProblemAliasService;

/**
 * Orchestrates {@code POST /v1/classify} and {@code POST /v1/confirmations}
 * (classify/confirmations contract, 2026-08-20). Unlike counsel, classify
 * takes no roster refs at all — just {@code inquiry_ref} and raw text — so
 * this service only needs the tenant alias, not student/class/parent aliases.
 */
@Service
public class InquiryClassificationService {

	private final ClassifyClient client;
	private final InquiryClassificationRepository classifications;
	private final AiProblemAliasService tenantAliases;
	private final CounselDraftRequestService drafts;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public InquiryClassificationService(
		ClassifyClient client,
		InquiryClassificationRepository classifications,
		AiProblemAliasService tenantAliases,
		CounselDraftRequestService drafts,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.client = client;
		this.classifications = classifications;
		this.tenantAliases = tenantAliases;
		this.drafts = drafts;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional
	public ClassifyResponse.Data classify(UUID teacherId, String inquiryRef, String rawBodyText) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		requireText(inquiryRef, "inquiryRef");
		requireText(rawBodyText, "text");
		String tenantAlias = tenantAliases.getOrCreateTenantAlias(resolvedTeacherId);

		ClassifyResponse response = call(() -> client.classify(
			new ClassifyRequest(inquiryRef, rawBodyText), tenantAlias, newRequestId()
		));

		Instant now = Instant.now(clock);
		ClassifyResponse.Data data = response.data();
		classifications.upsertPredicted(new NewClassification(
			UUID.randomUUID(), resolvedTeacherId, tenantAlias, inquiryRef,
			data.topic().wireValue(), data.sentiment().wireValue(), data.urgency().wireValue(),
			data.confidence().topic(), data.confidence().sentiment(), data.confidence().urgency(),
			data.classified(), data.fallbackReason() == null ? null : data.fallbackReason().wireValue(),
			response.meta() == null ? null : response.meta().executionId(), now, now
		));
		return data;
	}

	/**
	 * Confirms or corrects a classification. When {@code correctedTopic} is
	 * given, this also redrafts the counsel draft with the corrected topic and
	 * a fresh {@code Idempotency-Key} using the original inquiry's stored
	 * student/class/facts/labels — the classify/confirmations contract §4-2
	 * requires the backend to call both AI endpoints on a topic correction, not
	 * just record the correction. The returned {@link Optional} is empty when
	 * no redraft happened, either because the correction was not about the
	 * topic or because this inquiry never had a draft created for it.
	 *
	 * @param correctedTopic non-null only when the teacher changed the topic
	 * @param correctedSentiment non-null only when the teacher changed the sentiment
	 * @param correctedUrgency non-null only when the teacher changed the urgency
	 */
	@Transactional
	public Optional<CounselDraftService.CreateCounselDraftResult> confirm(
		UUID teacherId,
		String inquiryRef,
		ConfirmationAction action,
		CounselTopic correctedTopic,
		InquirySentiment correctedSentiment,
		CounselUrgency correctedUrgency
	) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		requireText(inquiryRef, "inquiryRef");
		if (action == null) throw CounselException.invalidRequest("action is required");
		boolean hasCorrection = correctedTopic != null || correctedSentiment != null || correctedUrgency != null;
		if (action == ConfirmationAction.CORRECTED && !hasCorrection) {
			throw CounselException.invalidRequest("action=corrected requires at least one corrected value");
		}
		if (action == ConfirmationAction.CONFIRMED && hasCorrection) {
			throw CounselException.invalidRequest("action=confirmed must not include a corrected value");
		}
		String tenantAlias = tenantAliases.getOrCreateTenantAlias(resolvedTeacherId);

		ConfirmationRequest request = action == ConfirmationAction.CORRECTED
			? ConfirmationRequest.corrected(inquiryRef, correctedTopic, correctedSentiment, correctedUrgency)
			: ConfirmationRequest.confirmed(inquiryRef);
		call(() -> client.confirm(request, tenantAlias, newRequestId()));

		classifications.recordConfirmation(
			resolvedTeacherId, inquiryRef, action.wireValue(),
			correctedTopic == null ? null : correctedTopic.wireValue(),
			correctedSentiment == null ? null : correctedSentiment.wireValue(),
			correctedUrgency == null ? null : correctedUrgency.wireValue(),
			Instant.now(clock)
		);

		if (correctedTopic == null) return Optional.empty();
		return drafts.redraftWithCorrectedTopic(resolvedTeacherId, inquiryRef, correctedTopic);
	}

	private <T> T call(Supplier<T> aiCall) {
		try {
			return aiCall.get();
		}
		catch (ClassifyClientException exception) {
			throw switch (exception.reason()) {
				case INVALID_REQUEST -> CounselException.invalidRequest("classify request was rejected: " + exception.getMessage());
				case NOT_FOUND -> CounselException.classificationNotFound();
				case INTERNAL_ERROR -> CounselException.upstreamInternalError(exception);
				case TIMEOUT, UPSTREAM_DOWN, HTTP_ERROR, EMPTY_RESPONSE, NETWORK_ERROR -> CounselException.upstreamUnavailable(exception);
			};
		}
	}

	private static String newRequestId() {
		return UUID.randomUUID().toString();
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) throw CounselException.invalidRequest(name + " must not be blank");
	}

	private static UUID requireTeacher(UUID teacherId) {
		if (teacherId == null) throw CounselException.invalidPrincipal();
		return teacherId;
	}
}
