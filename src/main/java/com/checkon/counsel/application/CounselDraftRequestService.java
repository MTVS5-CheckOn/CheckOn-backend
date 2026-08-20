package com.checkon.counsel.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;
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
 */
@Service
public class CounselDraftRequestService {

	private final CounselDraftService drafts;
	private final TeacherStudentRelationshipRepository relationships;
	private final ClassGroupRepository classes;
	private final AiStudentAliasService studentAliases;
	private final AiProblemAliasService tenantAndClassAliases;
	private final AiGuardianAliasService guardianAliases;
	private final StudentPersonalInformationRepository personalInformation;
	private final InquiryTextMaskingService masking;
	private final ProblemGenerationPayloadHasher hasher;
	private final ObjectMapper objectMapper;

	public CounselDraftRequestService(
		CounselDraftService drafts,
		TeacherStudentRelationshipRepository relationships,
		ClassGroupRepository classes,
		AiStudentAliasService studentAliases,
		AiProblemAliasService tenantAndClassAliases,
		AiGuardianAliasService guardianAliases,
		StudentPersonalInformationRepository personalInformation,
		InquiryTextMaskingService masking,
		ProblemGenerationPayloadHasher hasher,
		ObjectMapper objectMapper
	) {
		this.drafts = drafts;
		this.relationships = relationships;
		this.classes = classes;
		this.studentAliases = studentAliases;
		this.tenantAndClassAliases = tenantAndClassAliases;
		this.guardianAliases = guardianAliases;
		this.personalInformation = personalInformation;
		this.masking = masking;
		this.hasher = hasher;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public CounselDraftService.CreateCounselDraftResult createDraft(UUID teacherId, CreateCounselInquiryCommand command) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
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
		return drafts.createDraft(resolvedTeacherId, draftCommand);
	}

	@Transactional
	public CounselDraftGetResponse getDraft(UUID teacherId, String jobId) {
		UUID resolvedTeacherId = requireTeacher(teacherId);
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
		String tenantAlias = tenantAndClassAliases.getOrCreateTenantAlias(resolvedTeacherId);
		var command = new RefineCounselDraftCommand(tenantAlias, newRequestId(), idempotencyKey, jobId, instruction, turnNo);
		return drafts.refine(resolvedTeacherId, command);
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
