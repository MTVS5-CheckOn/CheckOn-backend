package com.checkon.counsel.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.infrastructure.persistence.CounselInquiryRepository;
import com.checkon.counsel.infrastructure.persistence.CounselInquiryRepository.NewInquiry;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.application.AiStudentAliasService;
import com.checkon.problem.application.AiProblemAliasService;
import com.checkon.problem.application.ProblemGenerationPayloadHasher;
import com.checkon.roster.domain.ClassGroupStatus;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.domain.StudentPersonalInformation;
import com.checkon.roster.infrastructure.persistence.ClassGroupRepository;
import com.checkon.roster.infrastructure.persistence.StudentPersonalInformationRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Teacher-facing entry point for the counsel draft flow. Resolves the AI
 * contract's opaque refs from real roster identifiers, masks the inquiry text
 * (§1-④ of the counsel contract — masking is the backend's responsibility),
 * and hands off to {@link CounselDraftService} for the actual AI calls.
 *
 * <p>Also keeps the original inquiry context ({@link CounselInquiryRepository})
 * so a later topic correction can redraft without the caller resending
 * student/class/facts/labels — see {@link #redraftWithCorrectedTopic}.
 */
@Service
public class CounselDraftRequestService {

	private final CounselDraftService drafts;
	private final CounselInquiryRepository inquiries;
	private final TeacherStudentRelationshipRepository relationships;
	private final ClassGroupRepository classes;
	private final AiStudentAliasService studentAliases;
	private final AiProblemAliasService tenantAndClassAliases;
	private final AiGuardianAliasService guardianAliases;
	private final StudentPersonalInformationRepository personalInformation;
	private final InquiryTextMaskingService masking;
	private final ProblemGenerationPayloadHasher hasher;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public CounselDraftRequestService(
		CounselDraftService drafts,
		CounselInquiryRepository inquiries,
		TeacherStudentRelationshipRepository relationships,
		ClassGroupRepository classes,
		AiStudentAliasService studentAliases,
		AiProblemAliasService tenantAndClassAliases,
		AiGuardianAliasService guardianAliases,
		StudentPersonalInformationRepository personalInformation,
		InquiryTextMaskingService masking,
		ProblemGenerationPayloadHasher hasher,
		TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.drafts = drafts;
		this.inquiries = inquiries;
		this.relationships = relationships;
		this.classes = classes;
		this.studentAliases = studentAliases;
		this.tenantAndClassAliases = tenantAndClassAliases;
		this.guardianAliases = guardianAliases;
		this.personalInformation = personalInformation;
		this.masking = masking;
		this.hasher = hasher;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public CounselDraftService.CreateCounselDraftResult createDraft(UUID teacherId, CreateCounselInquiryCommand command) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		if (command == null) throw CounselException.invalidRequest("request body is required");
		if (command.studentId() == null || command.classId() == null)
			throw CounselException.invalidRequest("studentId and classId are required");
		if (!relationships.existsByTeacherIdAndStudentIdAndStatus(
			resolvedTeacherId, command.studentId(), RelationshipStatus.ACTIVE
		)) throw CounselException.targetNotFound();
		var classGroup = classes.findByIdAndTeacherId(command.classId(), resolvedTeacherId)
			.filter(value -> value.status() == ClassGroupStatus.ACTIVE)
			.orElseThrow(CounselException::targetNotFound);

		String tenantAlias = tenantAndClassAliases.getOrCreateTenantAlias(resolvedTeacherId);
		String studentRef = studentAliases.getOrCreate(resolvedTeacherId, command.studentId());
		String classRef = tenantAndClassAliases.getOrCreateClassAlias(resolvedTeacherId, classGroup.id());
		String parentRef = guardianAliases.getOrCreate(resolvedTeacherId, command.studentId());

		String realName = personalInformation.findById(command.studentId())
			.map(StudentPersonalInformation::realName)
			.orElse(null);
		String maskedText = masking.mask(command.rawText(), realName == null ? List.of() : List.of(realName));

		String snapshotHash = hasher.sha256(writeJson(command.facts()));

		var draftCommand = new CreateCounselDraftCommand(
			tenantAlias, newRequestId(), command.idempotencyKey(), command.inquiryRef(),
			command.topic(), command.urgency(), command.receivedAt(), maskedText,
			studentRef, parentRef, classRef, command.labels(), command.dismissedSuggestions(),
			snapshotHash, command.periodLabel(), command.facts()
		);
		var result = drafts.createDraft(resolvedTeacherId, draftCommand);

		Instant now = Instant.now(clock);
		inquiries.upsert(new NewInquiry(
			UUID.randomUUID(), resolvedTeacherId, command.inquiryRef(), command.studentId(), command.classId(),
			command.topic().wireValue(), command.urgency().wireValue(), command.receivedAt().toInstant(),
			command.rawText(), command.labels() == null ? List.of() : command.labels(),
			command.dismissedSuggestions() == null ? List.of() : command.dismissedSuggestions(),
			command.periodLabel(), command.facts() == null ? List.of() : command.facts(), now, now
		));
		return result;
	}

	/**
	 * Redrafts with the same student/class/facts/labels as the original
	 * inquiry, but a corrected topic and a fresh {@code Idempotency-Key} —
	 * this is what a teacher's topic correction must trigger per the
	 * classify/confirmations contract §4-2 ("BE는 이 API와
	 * POST /v1/counsel/drafts를 둘 다 호출한다"). Returns empty if this
	 * inquiry never had a draft created for it (nothing to redraft from).
	 */
	@Transactional
	public Optional<CounselDraftService.CreateCounselDraftResult> redraftWithCorrectedTopic(
		UUID teacherId,
		String inquiryRef,
		CounselTopic correctedTopic
	) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		var stored = inquiries.findByTeacherAndInquiryRef(resolvedTeacherId, inquiryRef);
		if (stored.isEmpty()) return Optional.empty();
		var inquiry = stored.get();

		var command = new CreateCounselInquiryCommand(
			inquiry.studentId(), inquiry.classId(), newRequestId(), inquiryRef,
			correctedTopic, CounselUrgency.fromWireValue(inquiry.urgency()),
			inquiry.receivedAt().atOffset(ZoneOffset.UTC), inquiry.rawText(),
			inquiry.labels(), inquiry.dismissedSuggestions(), inquiry.periodLabel(), inquiry.facts()
		);
		return Optional.of(createDraft(resolvedTeacherId, command));
	}

	@Transactional
	public CounselDraftGetResponse getDraft(UUID teacherId, String jobId) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		String tenantAlias = tenantAndClassAliases.getOrCreateTenantAlias(resolvedTeacherId);
		return drafts.getDraft(resolvedTeacherId, tenantAlias, jobId, newRequestId());
	}

	@Transactional
	public CounselDraftRefineResponse refine(
		UUID teacherId,
		String jobId,
		String idempotencyKey,
		String instruction,
		Integer turnNo
	) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		String tenantAlias = tenantAndClassAliases.getOrCreateTenantAlias(resolvedTeacherId);
		var command = new RefineCounselDraftCommand(tenantAlias, newRequestId(), idempotencyKey, jobId, instruction, turnNo);
		return drafts.refine(resolvedTeacherId, command);
	}

	@Transactional
	public void markSent(UUID teacherId, String jobId, String sentText) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
		tenantContext.setCurrentTeacher(resolvedTeacherId);
		drafts.markSent(resolvedTeacherId, jobId, sentText);
	}

	private static String newRequestId() {
		return UUID.randomUUID().toString();
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
			throw new IllegalStateException("counsel context could not be serialized", exception);
		}
	}
}
